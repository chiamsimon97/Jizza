package com.jizza.engine.api.enums;

/**
 * {@code WAITING}: nothing can run until a wait step's time comes or a workflow it started
 * finishes. Nobody needs to answer anything; the run carries on by itself.
 */
public enum RunStatus { RUNNING, PAUSED_WAITING_INSTRUCTION, WAITING, DONE, FAILED, CANCELLED }
