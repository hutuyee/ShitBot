package haaa.shitbot.api;

/** Implemented by each platform plugin main class for API discovery. */
public interface ShitBotApiProvider {
    ShitBotApi getShitBotApi();
}
