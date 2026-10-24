package com.jizza.engine.workflow.sandbox;

import com.jizza.engine.api.workflow.functions.ScriptFunctionDto;

/** Finds the function a script calls as {@code fn.<name>(…)}: one of the run owner's, or null. */
@FunctionalInterface
public interface FunctionLookup {

  FunctionLookup NONE = name -> null;

  ScriptFunctionDto find(String name);
}
