package com.jizza.engine.api.workflow;

import com.jizza.engine.api.dto.SkillDto;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Storage for the skills that expose a workflow to an agent as a callable tool (R32).
 *
 * <p>One skill per workflow version: saving again for the same version replaces it rather than
 * accumulating rival entries. What a skill may be called and whether saving one is allowed is the
 * platform's business.
 *
 * <p>The engine does keep one rule for itself: a skill is invocable only while the workflow version
 * behind it is approved and enabled, re-evaluated on every status change. That is the Charter
 * invariant that nothing an agent authored runs before a human has approved it, so it is enforced
 * here rather than trusted to a caller.
 */
public interface SkillStore {

  SkillDto save(UUID workflowId, SkillSpec spec);

  List<SkillDto> list(UUID userId);

  Optional<SkillDto> find(UUID skillId);
}
