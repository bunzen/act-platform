package no.mnemonic.act.platform.service.ti.delegates;

import no.mnemonic.act.platform.api.exceptions.AccessDeniedException;
import no.mnemonic.act.platform.api.exceptions.AuthenticationFailedException;
import no.mnemonic.act.platform.api.exceptions.InvalidArgumentException;
import no.mnemonic.act.platform.api.exceptions.ObjectNotFoundException;
import no.mnemonic.act.platform.api.model.v1.Fact;
import no.mnemonic.act.platform.api.model.v1.Organization;
import no.mnemonic.act.platform.api.request.v1.RetractFactRequest;
import no.mnemonic.act.platform.dao.cassandra.entity.AccessMode;
import no.mnemonic.act.platform.dao.cassandra.entity.Direction;
import no.mnemonic.act.platform.dao.cassandra.entity.FactEntity;
import no.mnemonic.act.platform.dao.cassandra.entity.ObjectFactBindingEntity;
import no.mnemonic.act.platform.service.contexts.TriggerContext;
import no.mnemonic.act.platform.service.ti.TiFunctionConstants;
import no.mnemonic.act.platform.service.ti.TiRequestContext;
import no.mnemonic.act.platform.service.ti.TiSecurityContext;
import no.mnemonic.act.platform.service.ti.TiServiceEvent;
import no.mnemonic.act.platform.service.ti.helpers.FactStorageHelper;
import no.mnemonic.act.platform.service.ti.helpers.FactTypeResolver;
import no.mnemonic.commons.utilities.ObjectUtils;
import no.mnemonic.commons.utilities.collections.ListUtils;
import no.mnemonic.commons.utilities.collections.MapUtils;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static no.mnemonic.commons.utilities.collections.MapUtils.Pair.T;

public class FactRetractDelegate extends AbstractDelegate {

  private static final Map<AccessMode, Integer> ACCESS_MODE_ORDER = MapUtils.map(
          T(AccessMode.Public, 0),
          T(AccessMode.RoleBased, 1),
          T(AccessMode.Explicit, 2)
  );

  private final FactTypeResolver factTypeResolver;
  private final FactStorageHelper factStorageHelper;

  private FactRetractDelegate(FactTypeResolver factTypeResolver, FactStorageHelper factStorageHelper) {
    this.factTypeResolver = factTypeResolver;
    this.factStorageHelper = factStorageHelper;
  }

  public Fact handle(RetractFactRequest request)
          throws AccessDeniedException, AuthenticationFailedException, InvalidArgumentException, ObjectNotFoundException {
    // Fetch Fact to retract and verify that it exists.
    FactEntity factToRetract = fetchExistingFact(request.getFact());
    // Verify that user is allowed to access the Fact to retract.
    TiSecurityContext.get().checkReadPermission(factToRetract);
    // Verify that user is allowed to add Facts for the requested organization.
    TiSecurityContext.get().checkPermission(TiFunctionConstants.addFactObjects, resolveOrganization(request.getOrganization()));
    // Save everything in database.
    FactEntity retractionFact = saveRetractionFact(request, factToRetract);
    List<UUID> subjectsAddedToAcl = factStorageHelper.saveInitialAclForNewFact(retractionFact, request.getAcl());
    factStorageHelper.saveCommentForFact(retractionFact, request.getComment());
    // Index everything into ElasticSearch.
    indexCreatedFact(retractionFact, factTypeResolver.resolveRetractionFactType(), subjectsAddedToAcl);
    reindexExistingFact(factToRetract.getId(), d -> d.setRetracted(true));

    // Register TriggerEvent before returning Retraction Fact.
    Fact retractionFactParameter = TiRequestContext.get().getFactConverter().apply(retractionFact);
    Fact retractedFactParameter = TiRequestContext.get().getFactConverter().apply(factToRetract);
    registerTriggerEvent(retractionFactParameter, retractedFactParameter);

    return retractionFactParameter;
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {
    private FactTypeResolver factTypeResolver;
    private FactStorageHelper factStorageHelper;

    private Builder() {
    }

    public FactRetractDelegate build() {
      ObjectUtils.notNull(factTypeResolver, "Cannot instantiate FactRetractDelegate without 'factTypeResolver'.");
      ObjectUtils.notNull(factStorageHelper, "Cannot instantiate FactRetractDelegate without 'factStorageHelper'.");
      return new FactRetractDelegate(factTypeResolver, factStorageHelper);
    }

    public Builder setFactTypeResolver(FactTypeResolver factTypeResolver) {
      this.factTypeResolver = factTypeResolver;
      return this;
    }

    public Builder setFactStorageHelper(FactStorageHelper factStorageHelper) {
      this.factStorageHelper = factStorageHelper;
      return this;
    }
  }

  private FactEntity saveRetractionFact(RetractFactRequest request, FactEntity factToRetract) throws InvalidArgumentException {
    FactEntity retractionFact = new FactEntity()
            .setId(UUID.randomUUID()) // Need to provide client-generated ID.
            .setTypeID(factTypeResolver.resolveRetractionFactType().getId())
            .setValue(String.format("Retracted Fact with id = %s.", factToRetract.getId()))
            .setInReferenceToID(factToRetract.getId())
            .setOrganizationID(resolveOrganization(request.getOrganization()))
            .setSourceID(resolveSource(request.getSource()))
            .setAccessMode(resolveAccessMode(request, factToRetract))
            .setBindings(factToRetract.getBindings())
            .setTimestamp(System.currentTimeMillis())
            .setLastSeenTimestamp(System.currentTimeMillis());
    retractionFact = TiRequestContext.get().getFactManager().saveFact(retractionFact);

    // Also bind retraction Fact to each Object the retracted Fact is bound to.
    for (FactEntity.FactObjectBinding binding : ListUtils.list(factToRetract.getBindings())) {
      ObjectFactBindingEntity retractionFactBinding = new ObjectFactBindingEntity()
              .setFactID(retractionFact.getId())
              .setObjectID(binding.getObjectID())
              .setDirection(Direction.None);
      TiRequestContext.get().getObjectManager().saveObjectFactBinding(retractionFactBinding);
    }

    return retractionFact;
  }

  private AccessMode resolveAccessMode(RetractFactRequest request, FactEntity factToRetract) throws InvalidArgumentException {
    // If no AccessMode provided fall back to the AccessMode from the Fact to retract.
    AccessMode mode = ObjectUtils.ifNotNull(request.getAccessMode(), m -> AccessMode.valueOf(m.name()), factToRetract.getAccessMode());

    // The AccessMode of the Retraction Fact should not be less restrictive than the AccessMode of the Fact to retract.
    if (ACCESS_MODE_ORDER.get(mode) < ACCESS_MODE_ORDER.get(factToRetract.getAccessMode())) {
      throw new InvalidArgumentException()
              .addValidationError("Requested AccessMode cannot be less restrictive than AccessMode of the Fact to retract.",
                      "access.mode.too.wide", "accessMode", mode.name());
    }

    return mode;
  }

  private void registerTriggerEvent(Fact retractionFact, Fact retractedFact) {
    // The AccessMode of the Retraction Fact cannot be less restrictive than the AccessMode of the Fact to retract,
    // thus, it is safe to always include both Facts as context parameters when the event's AccessMode is set to
    // the AccessMode of the Retraction Fact (i.e. to the more restrictive AccessMode).
    TiServiceEvent event = TiServiceEvent.forEvent(TiServiceEvent.EventName.FactRetracted)
            .setOrganization(ObjectUtils.ifNotNull(retractionFact.getOrganization(), Organization.Info::getId))
            .setAccessMode(retractionFact.getAccessMode())
            .addContextParameter(TiServiceEvent.ContextParameter.RetractionFact.name(), retractionFact)
            .addContextParameter(TiServiceEvent.ContextParameter.RetractedFact.name(), retractedFact)
            .build();
    TriggerContext.get().registerTriggerEvent(event);
  }

}
