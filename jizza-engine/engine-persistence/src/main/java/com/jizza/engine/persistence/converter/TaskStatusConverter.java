package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.TaskStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TaskStatusConverter extends LowerCaseEnumConverter<TaskStatus> {
  public TaskStatusConverter() { super(TaskStatus.class); }
}
