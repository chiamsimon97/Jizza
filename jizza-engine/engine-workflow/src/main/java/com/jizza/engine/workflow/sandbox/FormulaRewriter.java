package com.jizza.engine.workflow.sandbox;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Turns the workflow builder's formula syntax into the JEXL the sandbox runs.
 *
 * <p>A formula calls the {@link Formatters} functions three ways, like Zoho Flow's formula editor:
 * <ul>
 *   <li>chained on a value, {@code input.name.trim().upper()};</li>
 *   <li>as a plain call, {@code upper(input.name)};</li>
 *   <li>with the namespace, {@code fmt:upper(input.name)}, as older workflows do.</li>
 * </ul>
 * The first two become the third. A call to one of the person's own functions, {@code fn.total(lines)},
 * becomes {@code fn:call('total', lines)} (see {@link FunctionCalls}). Everything else (references, operators, literals, other JEXL)
 * passes through untouched, so any expression that ran before runs the same way now.
 *
 * <p>This works on tokens rather than a full parse: a chained call's receiver is found by walking
 * back over the postfix chain before the dot, which is all the rewrite needs to know.
 */
public final class FormulaRewriter {

  /** The functions a formula may call by name: every public static method of {@link Formatters}. */
  public static final Set<String> FUNCTIONS = Arrays.stream(Formatters.class.getDeclaredMethods())
      .filter(m -> Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()))
      .map(Method::getName)
      .collect(Collectors.toUnmodifiableSet());

  private FormulaRewriter() {}

  private enum Kind { IDENT, NUMBER, STRING, PUNCT, SPACE }

  private record Token(Kind kind, String text) {
    boolean is(String s) { return kind == Kind.PUNCT && text.equals(s); }
  }

  /** The formula as JEXL; text that does not tokenize is returned as it is, for JEXL to report. */
  public static String toJexl(String formula) {
    if (formula == null || formula.indexOf('(') < 0) return formula;
    List<Token> tokens = tokenize(formula);
    if (tokens == null) return formula;
    functionCalls(tokens);
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.kind != Kind.IDENT || !FUNCTIONS.contains(t.text)) continue;
      int open = next(tokens, i);
      if (open < 0 || !tokens.get(open).is("(")) continue;
      int before = prev(tokens, i);
      Token prior = before < 0 ? null : tokens.get(before);
      if (prior != null && prior.is(":") && before == i - 1 && before > 0
          && tokens.get(before - 1).kind == Kind.IDENT) {
        continue; // already fmt:name(…)
      }
      if (prior != null && prior.is(".")) {
        int start = receiverStart(tokens, before);
        if (start < 0) continue;
        // receiver . name ( args  →  fmt:name( receiver , args
        List<Token> receiver = new ArrayList<>(tokens.subList(start, before));
        int close = matching(tokens, open);
        boolean noArgs = close > 0 && next(tokens, open) == close;
        List<Token> replacement = new ArrayList<>();
        replacement.add(new Token(Kind.IDENT, "fmt:" + t.text));
        replacement.add(new Token(Kind.PUNCT, "("));
        replacement.addAll(receiver);
        if (!noArgs) replacement.add(new Token(Kind.PUNCT, ", "));
        tokens.subList(start, open + 1).clear();
        tokens.addAll(start, replacement);
        i = start; // the receiver may itself hold calls already rewritten; carry on after the name
        continue;
      }
      tokens.set(i, new Token(Kind.IDENT, "fmt:" + t.text));
    }
    StringBuilder out = new StringBuilder();
    for (Token t : tokens) out.append(t.text);
    return out.toString();
  }

  /** fn . name ( args  →  fn:call( 'name' , args — in place. */
  private static void functionCalls(List<Token> tokens) {
    for (int i = 0; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.kind != Kind.IDENT || !t.text.equals("fn")) continue;
      int before = prev(tokens, i);
      if (before >= 0 && tokens.get(before).is(".")) continue;
      if (before == i - 1 && before > 0 && tokens.get(before).is(":") && tokens.get(before - 1).kind == Kind.IDENT) continue;
      int dot = next(tokens, i);
      if (dot < 0 || !tokens.get(dot).is(".")) continue;
      int name = next(tokens, dot);
      if (name < 0 || tokens.get(name).kind != Kind.IDENT) continue;
      int open = next(tokens, name);
      if (open < 0 || !tokens.get(open).is("(")) continue;
      boolean noArgs = next(tokens, open) >= 0 && tokens.get(next(tokens, open)).is(")");
      List<Token> replacement = new ArrayList<>(List.of(new Token(Kind.IDENT, "fn"), new Token(Kind.PUNCT, ":"),
          new Token(Kind.IDENT, "call"), new Token(Kind.PUNCT, "("), new Token(Kind.STRING, "'" + tokens.get(name).text + "'")));
      if (!noArgs) replacement.add(new Token(Kind.PUNCT, ", "));
      tokens.subList(i, open + 1).clear();
      tokens.addAll(i, replacement);
    }
  }

  /** Where the postfix chain that ends just before the dot at {@code dot} starts, or -1. */
  private static int receiverStart(List<Token> tokens, int dot) {
    int i = dot - 1;
    while (true) {
      if (i < 0) return -1;
      Token t = tokens.get(i);
      int start;
      if (t.is(")") || t.is("]")) {
        int open = matchingBack(tokens, i);
        if (open < 0) return -1;
        start = open;
        if (t.is(")")) {
          // a call: name(…) or ns:name(…); a bare (…) is a group
          if (open > 0 && tokens.get(open - 1).kind == Kind.IDENT) {
            start = open - 1;
            if (start > 1 && tokens.get(start - 1).is(":") && tokens.get(start - 2).kind == Kind.IDENT) start -= 2;
          }
        } else if (open > 0 && isValueEnd(tokens.get(open - 1))) {
          // an index, items[0]: keep walking back over what it indexes
          i = open - 1;
          continue;
        }
      } else if (t.kind == Kind.IDENT || t.kind == Kind.NUMBER || t.kind == Kind.STRING) {
        start = i;
        if (t.kind == Kind.IDENT && i > 1 && tokens.get(i - 1).is(":") && tokens.get(i - 2).kind == Kind.IDENT) start = i - 2;
      } else {
        return -1;
      }
      if (start > 0 && tokens.get(start - 1).is(".")) {
        i = start - 2;
        continue;
      }
      return start;
    }
  }

  private static boolean isValueEnd(Token t) {
    return t.kind == Kind.IDENT || t.kind == Kind.STRING || t.is(")") || t.is("]");
  }

  private static int matching(List<Token> tokens, int open) {
    int depth = 0;
    for (int i = open; i < tokens.size(); i++) {
      Token t = tokens.get(i);
      if (t.is("(") || t.is("[") || t.is("{")) depth++;
      else if (t.is(")") || t.is("]") || t.is("}")) {
        if (--depth == 0) return i;
      }
    }
    return -1;
  }

  private static int matchingBack(List<Token> tokens, int close) {
    int depth = 0;
    for (int i = close; i >= 0; i--) {
      Token t = tokens.get(i);
      if (t.is(")") || t.is("]") || t.is("}")) depth++;
      else if (t.is("(") || t.is("[") || t.is("{")) {
        if (--depth == 0) return i;
      }
    }
    return -1;
  }

  private static int next(List<Token> tokens, int i) {
    for (int j = i + 1; j < tokens.size(); j++) if (tokens.get(j).kind != Kind.SPACE) return j;
    return -1;
  }

  private static int prev(List<Token> tokens, int i) {
    for (int j = i - 1; j >= 0; j--) if (tokens.get(j).kind != Kind.SPACE) return j;
    return -1;
  }

  /** Splits a formula into tokens, or null when a string is left open. */
  private static List<Token> tokenize(String s) {
    List<Token> out = new ArrayList<>();
    int i = 0;
    while (i < s.length()) {
      char c = s.charAt(i);
      int start = i;
      if ((c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/')
          || (c == '#' && i + 1 < s.length() && s.charAt(i + 1) == '#')) {
        // a script's line comment, kept as it is and never rewritten
        while (i < s.length() && s.charAt(i) != '\n') i++;
        out.add(new Token(Kind.SPACE, s.substring(start, i)));
      } else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '*') {
        int end = s.indexOf("*/", i + 2);
        i = end < 0 ? s.length() : end + 2;
        out.add(new Token(Kind.SPACE, s.substring(start, i)));
      } else if (Character.isWhitespace(c)) {
        while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        out.add(new Token(Kind.SPACE, s.substring(start, i)));
      } else if (c == '\'' || c == '"') {
        i++;
        while (i < s.length() && s.charAt(i) != c) i += s.charAt(i) == '\\' ? 2 : 1;
        if (i >= s.length()) return null;
        i++;
        out.add(new Token(Kind.STRING, s.substring(start, i)));
      } else if (Character.isLetter(c) || c == '_' || c == '$') {
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '_' || s.charAt(i) == '$')) i++;
        out.add(new Token(Kind.IDENT, s.substring(start, i)));
      } else if (Character.isDigit(c)) {
        while (i < s.length() && (Character.isLetterOrDigit(s.charAt(i)) || s.charAt(i) == '.'
            && i + 1 < s.length() && Character.isDigit(s.charAt(i + 1)))) i++;
        out.add(new Token(Kind.NUMBER, s.substring(start, i)));
      } else {
        out.add(new Token(Kind.PUNCT, String.valueOf(c)));
        i++;
      }
    }
    return out;
  }
}
