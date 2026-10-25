package haaa.shitbot.core.service;

import haaa.shitbot.api.PlayerBinding;
import haaa.shitbot.api.ShitBotApi;
import haaa.shitbot.core.config.Translations;
import haaa.shitbot.core.util.FutureUtil;
import haaa.shitbot.core.util.TextUtil;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Shared admin command parser; platform entry points enforce shitbot.admin before calling. */
public final class WhitelistCommand {
    private WhitelistCommand() { }

    public static CompletableFuture<String> execute(ShitBotApi api, Translations text, String[] args) {
        try {
            return dispatch(api, text, args).exceptionally(error -> text.format(
                    "admin.whitelist.failed", "%error%", TextUtil.singleLine(
                            FutureUtil.unwrap(error).getMessage(), 200)));
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(text.get("admin.whitelist.usage"));
        }
    }

    private static CompletableFuture<String> dispatch(ShitBotApi api, Translations text, String[] args) {
        if (args.length < 2) return CompletableFuture.completedFuture(text.get("admin.whitelist.usage"));
        String action = args[1].toLowerCase(Locale.ROOT);
        if ("add".equals(action) && args.length >= 4) {
            String qq = "-".equals(args[2]) ? null : args[2];
            return api.addWhitelist(join(args, 3), qq).thenApply(result -> text.get(
                    "admin.whitelist." + result.getStatus().name().toLowerCase(Locale.ROOT).replace('_', '-')));
        }
        if ("remove".equals(action) && args.length >= 3) {
            return api.removeWhitelist(join(args, 2)).thenApply(value -> text.get(value.isPresent()
                    ? "admin.whitelist.removed" : "admin.whitelist.not-found"));
        }
        if ("get".equals(action) && args.length >= 3) {
            return api.getBinding(join(args, 2)).thenApply(value -> value.isPresent()
                    ? describe(value.get(), text) : text.get("admin.whitelist.not-found"));
        }
        if (("qq".equals(action) || "remove-qq".equals(action)) && args.length == 3) {
            if (!TextUtil.isValidQqId(args[2])) throw new IllegalArgumentException("Invalid QQ");
            return "qq".equals(action)
                    ? api.getBindingsByQq(args[2]).thenApply(values -> describe(values, text))
                    : api.removeBindingsByQq(args[2]).thenApply(values -> text.format(
                            "admin.whitelist.removed-qq", "%count%", String.valueOf(values.size())));
        }
        if ("list".equals(action) && args.length <= 3) {
            int page = args.length == 3 ? Integer.parseInt(args[2]) : 1;
            if (page < 1 || page > Integer.MAX_VALUE / 20) throw new IllegalArgumentException("Invalid page");
            return api.getWhitelist((page - 1) * 20, 20).thenApply(values ->
                    text.format("admin.whitelist.page", "%page%", String.valueOf(page))
                            + "\n" + describe(values, text));
        }
        return CompletableFuture.completedFuture(text.get("admin.whitelist.usage"));
    }

    private static String join(String[] args, int start) {
        StringBuilder result = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) result.append(' ');
            result.append(args[i]);
        }
        return result.toString();
    }

    private static String describe(PlayerBinding binding, Translations text) {
        return text.format("admin.whitelist.entry", "%player%", binding.getPlayerName(),
                "%qq%", binding.getQqId().orElse(text.get("admin.whitelist.no-qq")));
    }

    private static String describe(List<PlayerBinding> bindings, Translations text) {
        if (bindings.isEmpty()) return text.get("admin.whitelist.not-found");
        StringBuilder result = new StringBuilder();
        for (PlayerBinding binding : bindings) {
            if (result.length() > 0) result.append('\n');
            result.append(describe(binding, text));
        }
        return result.toString();
    }
}
