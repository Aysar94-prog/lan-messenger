package net.lanmsg.chat;
import java.util.*;

/** Test fixture only: a minimal, purpose-built JSON reader for the shared voice_messages
 * manifest (no JSON library exists anywhere else in this codebase). Not a general-purpose
 * parser -- just enough for objects, arrays, strings, numbers (long or double), booleans and
 * null, matching standard JSON syntax. */
final class MiniJson {
  private final String s; private int i;
  private MiniJson(String s) { this.s = s; }

  static Object parse(String text) {
    MiniJson p = new MiniJson(text);
    p.skipWs();
    Object value = p.readValue();
    p.skipWs();
    return value;
  }

  private void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

  private Object readValue() {
    char c = s.charAt(i);
    if (c == '{') return readObject();
    if (c == '[') return readArray();
    if (c == '"') return readString();
    if (c == 't') { expect("true"); return Boolean.TRUE; }
    if (c == 'f') { expect("false"); return Boolean.FALSE; }
    if (c == 'n') { expect("null"); return null; }
    return readNumber();
  }

  private void expect(String literal) {
    if (!s.regionMatches(i, literal, 0, literal.length())) throw new IllegalStateException("expected " + literal + " at " + i);
    i += literal.length();
  }

  private Map<String, Object> readObject() {
    Map<String, Object> map = new LinkedHashMap<>();
    i++; skipWs();
    if (s.charAt(i) == '}') { i++; return map; }
    while (true) {
      skipWs();
      String key = readString();
      skipWs(); if (s.charAt(i) != ':') throw new IllegalStateException("expected ':' at " + i); i++;
      skipWs();
      map.put(key, readValue());
      skipWs();
      char c = s.charAt(i);
      if (c == ',') { i++; continue; }
      if (c == '}') { i++; break; }
      throw new IllegalStateException("expected ',' or '}' at " + i);
    }
    return map;
  }

  private List<Object> readArray() {
    List<Object> list = new ArrayList<>();
    i++; skipWs();
    if (s.charAt(i) == ']') { i++; return list; }
    while (true) {
      skipWs();
      list.add(readValue());
      skipWs();
      char c = s.charAt(i);
      if (c == ',') { i++; continue; }
      if (c == ']') { i++; break; }
      throw new IllegalStateException("expected ',' or ']' at " + i);
    }
    return list;
  }

  private String readString() {
    if (s.charAt(i) != '"') throw new IllegalStateException("expected string at " + i);
    i++;
    StringBuilder sb = new StringBuilder();
    while (true) {
      char c = s.charAt(i++);
      if (c == '"') break;
      if (c == '\\') {
        char esc = s.charAt(i++);
        switch (esc) {
          case '"': sb.append('"'); break;
          case '\\': sb.append('\\'); break;
          case '/': sb.append('/'); break;
          case 'b': sb.append('\b'); break;
          case 'f': sb.append('\f'); break;
          case 'n': sb.append('\n'); break;
          case 'r': sb.append('\r'); break;
          case 't': sb.append('\t'); break;
          case 'u': sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; break;
          default: throw new IllegalStateException("bad escape at " + i);
        }
      } else sb.append(c);
    }
    return sb.toString();
  }

  private Object readNumber() {
    int start = i;
    boolean isDouble = false;
    if (s.charAt(i) == '-') i++;
    while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
    if (i < s.length() && s.charAt(i) == '.') { isDouble = true; i++; while (i < s.length() && Character.isDigit(s.charAt(i))) i++; }
    if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
      isDouble = true; i++;
      if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
      while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
    }
    String text = s.substring(start, i);
    if (isDouble) return Double.parseDouble(text);
    try { return Long.parseLong(text); } catch (NumberFormatException overflow) { return Double.parseDouble(text); }
  }

  @SuppressWarnings("unchecked")
  static Map<String, Object> asObject(Object o) { return (Map<String, Object>) o; }
  @SuppressWarnings("unchecked")
  static List<Object> asArray(Object o) { return (List<Object>) o; }
  static long asLong(Object o) { return o instanceof Long ? (Long) o : (long) (double) (Double) o; }
  static boolean asBool(Object o) { return (Boolean) o; }
  static String asString(Object o) { return (String) o; }
}
