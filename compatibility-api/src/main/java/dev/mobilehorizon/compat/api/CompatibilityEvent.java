package dev.mobilehorizon.compat.api;

/** Immutable event raised by the Horizon bridge for a selected game instance. */
public final class CompatibilityEvent {
    private final long sequence;
    private final String type;
    private final String payloadJson;

    public CompatibilityEvent(long sequence, String type, String payloadJson) {
        if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
        this.sequence = sequence;
        this.type = requireText(type, "type", 96);
        this.payloadJson = payloadJson == null ? "{}" : payloadJson;
        if (this.payloadJson.length() > 256 * 1024) {
            throw new IllegalArgumentException("event payload exceeds 256 KiB");
        }
    }

    public long sequence() {
        return sequence;
    }

    public String type() {
        return type;
    }

    /** Raw JSON to keep the API independent from a particular JSON library. */
    public String payloadJson() {
        return payloadJson;
    }

    private static String requireText(String value, String field, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) {
                throw new IllegalArgumentException(field + " contains control characters");
            }
        }
        return value;
    }
}
