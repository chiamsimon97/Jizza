package com.jizza.engine.api.connector;

import com.jizza.engine.api.dto.GeneratedToolDto;
import com.jizza.engine.api.enums.DocFormat;
import java.util.List;
import java.util.UUID;

/** Turns an attached API document into one callable tool per endpoint (R26). */
public interface ToolGenerator {
  List<GeneratedToolDto> fromApiDoc(UUID connectionId, byte[] doc, DocFormat fmt);
}
