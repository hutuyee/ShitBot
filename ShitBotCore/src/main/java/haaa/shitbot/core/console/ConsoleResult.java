package haaa.shitbot.core.console;

public final class ConsoleResult {
    public enum Status {
        SUCCESS,
        FAILED,
        NO_PERMISSION,
        UNAVAILABLE,
        RESULT_TIMEOUT
    }

    private final String requestId;
    private final Status status;
    private final String output;
    private final String source;

    public ConsoleResult(String requestId, Status status, String output, String source) {
        this.requestId = requestId == null ? "" : requestId;
        this.status = status == null ? Status.FAILED : status;
        this.output = output == null ? "" : output;
        this.source = source == null ? "" : source;
    }

    public static ConsoleResult unavailable(ConsoleRequest request, String output, String source) {
        return new ConsoleResult(request == null ? "" : request.getRequestId(),
                Status.UNAVAILABLE, output, source);
    }

    public String getRequestId() { return requestId; }
    public Status getStatus() { return status; }
    public String getOutput() { return output; }
    public String getSource() { return source; }
    public boolean isSuccess() { return status == Status.SUCCESS; }
}
