package com.jizza.engine.persistence.converter;

import com.jizza.engine.api.enums.JobStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class JobStatusConverter extends LowerCaseEnumConverter<JobStatus> {
  public JobStatusConverter() { super(JobStatus.class); }
}
