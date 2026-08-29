package com.jizza.common.connector;

import com.jizza.common.dto.GeneratedToolDto;
import com.jizza.common.enums.DocFormat;
import java.util.List;
import java.util.UUID;

/** Turns an attached API document into one callable tool per endpoint (R26). */
public interface ToolGenerator {
  List<GeneratedToolDto> fromApiDoc(UUID connectionId, byte[] doc, DocFormat fmt);
}
