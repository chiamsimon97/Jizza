package com.jizza.engine.api.service;

import com.jizza.engine.api.service.ServiceModel.Action;
import com.jizza.engine.api.service.ServiceModel.ActionInput;
import com.jizza.engine.api.service.ServiceModel.Api;
import com.jizza.engine.api.service.ServiceModel.ApiInput;
import com.jizza.engine.api.service.ServiceModel.Info;
import com.jizza.engine.api.service.ServiceModel.InfoPatch;
import com.jizza.engine.api.service.ServiceModel.Resource;
import com.jizza.engine.api.service.ServiceModel.ResourceInput;
import com.jizza.engine.api.service.ServiceModel.Run;
import com.jizza.engine.api.service.ServiceModel.Trigger;
import com.jizza.engine.api.service.ServiceModel.TriggerInput;
import com.jizza.engine.api.service.ServiceModel.Variable;
import com.jizza.engine.api.service.ServiceModel.VariableInput;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for service definitions. Policy-free, like {@code ConnectionStore}: ownership checks,
 * validation of what makes a sensible definition and auditing live in the platform. Every child
 * lookup is scoped by the service id, so an id from one service never reaches into another.
 */
public interface ServiceDefinitions {

  List<Info> list(UUID userId);

  Optional<Info> find(UUID serviceId);

  Info update(UUID serviceId, InfoPatch patch);

  Info setVersionStatus(UUID serviceId, String versionStatus);

  List<Api> apis(UUID serviceId);

  Api saveApi(UUID serviceId, UUID apiId, ApiInput input);

  void deleteApi(UUID serviceId, UUID apiId);

  List<Resource> resources(UUID serviceId);

  Resource saveResource(UUID serviceId, UUID resourceId, ResourceInput input);

  void deleteResource(UUID serviceId, UUID resourceId);

  List<Action> actions(UUID serviceId);

  Optional<Action> action(UUID actionId);

  Action saveAction(UUID serviceId, UUID actionId, ActionInput input);

  void deleteAction(UUID serviceId, UUID actionId);

  List<Trigger> triggers(UUID serviceId);

  Trigger saveTrigger(UUID serviceId, UUID triggerId, TriggerInput input);

  void deleteTrigger(UUID serviceId, UUID triggerId);

  List<Variable> variables(UUID serviceId);

  Variable saveVariable(UUID serviceId, UUID variableId, VariableInput input);

  void deleteVariable(UUID serviceId, UUID variableId);

  List<Run> runs(UUID serviceId, int limit);
}
