package com.jizza.persistence.converter;

import com.jizza.common.enums.TaskStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class TaskStatusConverter extends LowerCaseEnumConverter<TaskStatus> {
  public TaskStatusConverter() { super(TaskStatus.class); }
}
