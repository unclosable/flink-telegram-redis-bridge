package io.github.flinktelegrambridge.protocol;

/**
 * Outcome of parsing a harness envelope from a stream entry body.
 */
public final class HarnessParseResult {

    public enum Status {
        /** Envelope parsed successfully and its {@code type} is recognized. */
        VALID,
        /** Envelope parsed but its {@code type} is unknown (safe-ignore). */
        UNSUPPORTED_TYPE,
        /** Envelope could not be parsed, or had a missing/unsupported version. */
        MALFORMED
    }

    private final Status status;
    private final HarnessEnvelope envelope;
    private final String reason;

    private HarnessParseResult(Status status, HarnessEnvelope envelope, String reason) {
        this.status = status;
        this.envelope = envelope;
        this.reason = reason;
    }

    public static HarnessParseResult valid(HarnessEnvelope envelope) {
        return new HarnessParseResult(Status.VALID, envelope, null);
    }

    public static HarnessParseResult unsupportedType(HarnessEnvelope envelope) {
        return new HarnessParseResult(
                Status.UNSUPPORTED_TYPE, envelope, "unsupported type: " + envelope.type());
    }

    public static HarnessParseResult malformed(String reason) {
        return new HarnessParseResult(Status.MALFORMED, null, reason);
    }

    public Status status() {
        return status;
    }

    public HarnessEnvelope envelope() {
        return envelope;
    }

    public String reason() {
        return reason;
    }

    public boolean isValid() {
        return status == Status.VALID;
    }

    public boolean isUnsupportedType() {
        return status == Status.UNSUPPORTED_TYPE;
    }

    public boolean isMalformed() {
        return status == Status.MALFORMED;
    }

    /**
     * The parsed envelope {@code type}, or {@code null} when the payload was
     * malformed and no envelope could be reconstructed.
     */
    public String type() {
        return envelope == null ? null : envelope.type();
    }
}
