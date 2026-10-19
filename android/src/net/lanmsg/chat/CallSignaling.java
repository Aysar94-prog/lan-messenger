package net.lanmsg.chat;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Constructs/parses call-signaling frames (4-byte big-endian length + UTF-8 JSON).
 *  Pure Java — no Android dependency. */
public final class CallSignaling {

  private CallSignaling() {}

  // ── JSON helpers (minimal, no external library) ────────────────

  private static String esc(String s) {
    if (s == null) return "null";
    StringBuilder sb = new StringBuilder(s.length() + 2);
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"':  sb.append("\\\""); break;
        case '\\': sb.append("\\\\"); break;
        case '\n': sb.append("\\n");  break;
        case '\r': sb.append("\\r");  break;
        case '\t': sb.append("\\t");  break;
        default:
          if (c < 0x20) sb.append(String.format("\\u%04x", (int)c));
          else sb.append(c);
      }
    }
    sb.append('"');
    return sb.toString();
  }

  private static String jsonValue(Object v) {
    if (v == null) return "null";
    if (v instanceof String) return esc((String)v);
    if (v instanceof Number || v instanceof Boolean) return v.toString();
    if (v instanceof Map) return jsonObject((Map<?,?>)v);
    if (v instanceof List) return jsonArray((List<?>)v);
    return esc(v.toString());
  }

  private static String jsonObject(Map<?,?> m) {
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<?,?> e : m.entrySet()) {
      if (!first) sb.append(",");
      first = false;
      sb.append(esc(e.getKey().toString())).append(":").append(jsonValue(e.getValue()));
    }
    sb.append("}");
    return sb.toString();
  }

  private static String jsonArray(List<?> a) {
    StringBuilder sb = new StringBuilder("[");
    for (int i = 0; i < a.size(); i++) {
      if (i > 0) sb.append(",");
      sb.append(jsonValue(a.get(i)));
    }
    sb.append("]");
    return sb.toString();
  }

  // ── Serialize ──────────────────────────────────────────────────

  /** Serialize a Frame to wire bytes: 4-byte big-endian length + UTF-8 JSON. */
  public static byte[] serialize(CallProtocol.Frame frame) {
    Map<String,Object> root = new LinkedHashMap<>();
    root.put("v",  frame.protocolVersion);
    root.put("t",  frame.type);
    root.put("cid", frame.callId);
    root.put("seq", frame.senderSequence);
    root.put("gen", frame.negotiationGeneration);
    if (frame.body != null && !frame.body.isEmpty())
      root.put("b", frame.body);

    String json = jsonObject(root);
    byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
    if (utf8.length > CallProtocol.MAX_FRAME_BYTES)
      throw new IllegalArgumentException("Frame exceeds " + CallProtocol.MAX_FRAME_BYTES + " bytes");

    ByteBuffer buf = ByteBuffer.allocate(4 + utf8.length);
    buf.putInt(utf8.length);
    buf.put(utf8);
    return buf.array();
  }

  // ── Parse ──────────────────────────────────────────────────────

  /** Parse wire bytes into a Frame. Returns null on any parse failure. */
  public static CallProtocol.Frame parse(byte[] wire) {
    if (wire == null || wire.length < 4) return null;
    int length = ByteBuffer.wrap(wire, 0, 4).getInt();
    if (length < 0 || length > CallProtocol.MAX_FRAME_BYTES || 4 + length != wire.length)
      return null;

    String json;
    try { json = StandardCharsets.UTF_8.newDecoder()
      .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
      .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
      .decode(ByteBuffer.wrap(wire, 4, length)).toString(); }
    catch (java.nio.charset.CharacterCodingException e) { return null; }
    Map<String,Object> root;
    try { root = parseJsonObject(json); }
    catch (Exception e) { return null; }

    CallProtocol.Frame f = new CallProtocol.Frame();
    try {
      if (Long.valueOf(2).equals(root.get("v"))) {
        if (!root.keySet().equals(new HashSet<String>(Arrays.asList("v","t","cid","seq","gen")))
            && !root.keySet().equals(new HashSet<String>(Arrays.asList("v","t","cid","seq","gen","b")))) return null;
        if (!(root.get("seq") instanceof Long) || !(root.get("gen") instanceof Long)) return null;
      }
      Object v = root.get("v");   f.protocolVersion = v instanceof Number ? ((Number)v).intValue() : 1;
      if (f.protocolVersion == 2 && !Long.valueOf(2).equals(v)) return null;
      f.type       = stringField(root, "t");
      f.callId     = stringField(root, "cid");
      Object s = root.get("seq"); f.senderSequence = s instanceof Number ? ((Number)s).longValue() : 0;
      Object g = root.get("gen"); f.negotiationGeneration = g instanceof Number ? ((Number)g).longValue() : 0;

      @SuppressWarnings("unchecked")
      Map<String,Object> body = (Map<String,Object>)root.get("b");
      f.body = body != null ? body : new LinkedHashMap<>();
    } catch (Exception e) {
      return null;
    }

    if (f.type == null || f.callId == null) return null;
    if (!CallProtocol.validCallId(f.callId)) return null;

    return f;
  }

  // ── JSON parser (minimal, recursive-descent) ───────────────────

  private static class JsonParser {
    final String src;
    int pos;
    int depth;

    JsonParser(String s) { this.src = s; this.pos = 0; }

    char peek() { skipWhitespace(); return pos < src.length() ? src.charAt(pos) : 0; }
    char read() { skipWhitespace(); if (pos >= src.length()) throw new RuntimeException("Unexpected EOF"); return src.charAt(pos++); }
    void expect(char c) { char r = read(); if (r != c) throw new RuntimeException("Expected '" + c + "', got '" + r + "'"); }
    void skipWhitespace() { while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) pos++; }

    Object parseValue() {
      if (++depth > 16) throw new RuntimeException("JSON nesting limit");
      try {
      char c = peek();
      if (c == '"') return parseString();
      if (c == '{') return parseObject();
      if (c == '[') return parseArray();
      if (c == 't' || c == 'f') return parseBoolean();
      if (c == 'n') { parseNull(); return null; }
      return parseNumber();
      } finally { --depth; }
    }

    String parseString() {
      expect('"');
      StringBuilder sb = new StringBuilder();
      while (pos < src.length()) {
        char c = src.charAt(pos++);
        if (c == '"') return sb.toString();
        if (c == '\\') {
          if (pos >= src.length()) throw new RuntimeException("Escape at EOF");
          char e = src.charAt(pos++);
          switch (e) {
            case '"': case '\\': case '/': sb.append(e); break;
            case 'n': sb.append('\n'); break;
            case 'r': sb.append('\r'); break;
            case 't': sb.append('\t'); break;
            case 'b': sb.append('\b'); break;
            case 'f': sb.append('\f'); break;
            case 'u':
              if (pos + 4 > src.length()) throw new RuntimeException("\\u at EOF");
              sb.append((char)Integer.parseInt(src.substring(pos, pos+4), 16));
              pos += 4;
              break;
            default: throw new RuntimeException("Invalid JSON escape");
          }
        } else {
          if (c < 0x20) throw new RuntimeException("Unescaped control character");
          sb.append(c);
        }
      }
      throw new RuntimeException("Unterminated string");
    }

    Map<String,Object> parseObject() {
      expect('{');
      Map<String,Object> m = new LinkedHashMap<>();
      if (peek() == '}') { read(); return m; }
      while (true) {
        String key = parseString();
        expect(':');
        if (m.containsKey(key)) throw new RuntimeException("Duplicate JSON field");
        m.put(key, parseValue());
        if (peek() == '}') { read(); return m; }
        expect(',');
      }
    }

    List<Object> parseArray() {
      expect('[');
      List<Object> a = new ArrayList<>();
      if (peek() == ']') { read(); return a; }
      while (true) {
        a.add(parseValue());
        if (peek() == ']') { read(); return a; }
        expect(',');
      }
    }

    Boolean parseBoolean() {
      if (src.startsWith("true", pos))  { pos += 4; return Boolean.TRUE; }
      if (src.startsWith("false", pos)) { pos += 5; return Boolean.FALSE; }
      throw new RuntimeException("Expected boolean");
    }

    void parseNull() {
      if (src.startsWith("null", pos)) pos += 4;
      else throw new RuntimeException("Expected null");
    }

    Number parseNumber() {
      int start = pos;
      if (pos < src.length() && src.charAt(pos) == '-') pos++;
      while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
      boolean isFloat = false;
      if (pos < src.length() && src.charAt(pos) == '.') { pos++; isFloat = true;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
      }
      if (pos < src.length() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) { pos++; isFloat = true;
        if (pos < src.length() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
        while (pos < src.length() && Character.isDigit(src.charAt(pos))) pos++;
      }
      String num = src.substring(start, pos);
      if (!num.matches("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?"))
        throw new RuntimeException("Invalid JSON number");
      // An arithmetic conditional promotes Long to Double and loses large counters.
      if (isFloat) return Double.valueOf(num);
      return Long.valueOf(num);
    }
  }

  static Map<String,Object> parseJsonObject(String json) {
    JsonParser p = new JsonParser(json);
    Object val = p.parseValue();
    p.skipWhitespace();
    if (p.pos != json.length()) throw new RuntimeException("Trailing JSON data");
    if (!(val instanceof Map))
      throw new RuntimeException("Top-level JSON must be an object");
    @SuppressWarnings("unchecked")
    Map<String,Object> m = (Map<String,Object>)val;
    return m;
  }

  // ── Field helpers ──────────────────────────────────────────────

  private static String stringField(Map<String,Object> m, String key) {
    Object v = m.get(key);
    return v instanceof String ? (String)v : null;
  }

  // ── Convenience builders ───────────────────────────────────────

  /** Build an INVITE frame. */
  public static CallProtocol.Frame invite(String callId, long seq, String callerId, String calleeId) {
    CallProtocol.Frame f = new CallProtocol.Frame(CallProtocol.INVITE, callId, seq, 0);
    f.body = new LinkedHashMap<>();
    f.body.put("caller", callerId);
    f.body.put("callee", calleeId);
    return f;
  }

  /** Build a RINGING frame. */
  public static CallProtocol.Frame ringing(String callId, long seq) {
    return new CallProtocol.Frame(CallProtocol.RINGING, callId, seq, 0);
  }

  /** Build an ACCEPT frame. */
  public static CallProtocol.Frame accept(String callId, long seq) {
    return new CallProtocol.Frame(CallProtocol.ACCEPT, callId, seq, 0);
  }

  /** Build a DECLINE frame. */
  public static CallProtocol.Frame decline(String callId, long seq) {
    return new CallProtocol.Frame(CallProtocol.DECLINE, callId, seq, 0);
  }

  /** Build a BUSY frame. */
  public static CallProtocol.Frame busy(String callId, long seq) {
    return new CallProtocol.Frame(CallProtocol.BUSY, callId, seq, 0);
  }

  /** Build a CANCEL frame. */
  public static CallProtocol.Frame cancel(String callId, long seq) {
    return new CallProtocol.Frame(CallProtocol.CANCEL, callId, seq, 0);
  }

  /** Build an OFFER frame with SDP. */
  public static CallProtocol.Frame offer(String callId, long seq, long gen, String sdp) {
    CallProtocol.Frame f = new CallProtocol.Frame(CallProtocol.OFFER, callId, seq, gen);
    f.body = new LinkedHashMap<>();
    f.body.put("sdp", sdp);
    return f;
  }

  /** Build an ANSWER frame with SDP. */
  public static CallProtocol.Frame answer(String callId, long seq, long gen, String sdp) {
    CallProtocol.Frame f = new CallProtocol.Frame(CallProtocol.ANSWER, callId, seq, gen);
    f.body = new LinkedHashMap<>();
    f.body.put("sdp", sdp);
    return f;
  }

  /** Build an ICE candidate frame. */
  public static CallProtocol.Frame ice(String callId, long seq, long gen,
                                        String candidate, String sdpMid, int sdpMLineIndex) {
    CallProtocol.Frame f = new CallProtocol.Frame(CallProtocol.ICE, callId, seq, gen);
    f.body = new LinkedHashMap<>();
    f.body.put("candidate", candidate);
    f.body.put("sdpMid", sdpMid);
    f.body.put("sdpMLineIndex", sdpMLineIndex);
    return f;
  }

  /** Build a HANGUP frame. */
  public static CallProtocol.Frame hangup(String callId, long seq) {
    return new CallProtocol.Frame(CallProtocol.HANGUP, callId, seq, 0);
  }

  /** Build a PING frame. */
  public static CallProtocol.Frame ping(String callId, long seq) {
    return new CallProtocol.Frame(CallProtocol.PING, callId, seq, 0);
  }

  /** Build a PONG frame. */
  public static CallProtocol.Frame pong(String callId, long seq) {
    return new CallProtocol.Frame(CallProtocol.PONG, callId, seq, 0);
  }

  // ── Body field extractors ──────────────────────────────────────

  public static String getSdp(CallProtocol.Frame f) {
    return f.body != null ? stringField(f.body, "sdp") : null;
  }

  public static String getCandidate(CallProtocol.Frame f) {
    return f.body != null ? stringField(f.body, "candidate") : null;
  }

  public static String getCaller(CallProtocol.Frame f) {
    return f.body != null ? stringField(f.body, "caller") : null;
  }

  public static String getCallee(CallProtocol.Frame f) {
    return f.body != null ? stringField(f.body, "callee") : null;
  }
}
