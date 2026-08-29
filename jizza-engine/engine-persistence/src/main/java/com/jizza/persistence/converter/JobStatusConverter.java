package com.jizza.persistence.converter;

import com.jizza.common.enums.JobStatus;
import jakarta.persistence.Converter;

@Converter(autoApply = true)
public class JobStatusConverter extends LowerCaseEnumConverter<JobStatus> {
  public JobStatusConverter() { super(JobStatus.class); }
}
