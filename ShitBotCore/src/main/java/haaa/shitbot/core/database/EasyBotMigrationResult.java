package haaa.shitbot.core.database;

import haaa.shitbot.core.config.Translations;

/** Immutable summary returned after importing EasyBot binding records. */
public final class EasyBotMigrationResult {
    private final int totalRows;
    private final int qqRows;
    private final int linkedPlayerRows;
    private final int fallbackRows;
    private final int importedRows;
    private final int alreadyPresentRows;
    private final int playerConflictRows;
    private final int qqConflictRows;
    private final int invalidRows;
    private final int nonQqRows;

    public EasyBotMigrationResult(int totalRows, int qqRows, int linkedPlayerRows, int fallbackRows,
                                  int importedRows, int alreadyPresentRows, int playerConflictRows,
                                  int qqConflictRows, int invalidRows, int nonQqRows) {
        this.totalRows = totalRows;
        this.qqRows = qqRows;
        this.linkedPlayerRows = linkedPlayerRows;
        this.fallbackRows = fallbackRows;
        this.importedRows = importedRows;
        this.alreadyPresentRows = alreadyPresentRows;
        this.playerConflictRows = playerConflictRows;
        this.qqConflictRows = qqConflictRows;
        this.invalidRows = invalidRows;
        this.nonQqRows = nonQqRows;
    }

    public int getTotalRows() { return totalRows; }
    public int getQqRows() { return qqRows; }
    public int getLinkedPlayerRows() { return linkedPlayerRows; }
    public int getFallbackRows() { return fallbackRows; }
    public int getImportedRows() { return importedRows; }
    public int getAlreadyPresentRows() { return alreadyPresentRows; }
    public int getPlayerConflictRows() { return playerConflictRows; }
    public int getQqConflictRows() { return qqConflictRows; }
    public int getInvalidRows() { return invalidRows; }
    public int getNonQqRows() { return nonQqRows; }

    public String describe(Translations translations) {
        return translations.format("migration.summary",
                "%accounts%", String.valueOf(totalRows),
                "%qq_accounts%", String.valueOf(qqRows),
                "%linked_players%", String.valueOf(linkedPlayerRows),
                "%fallback_names%", String.valueOf(fallbackRows),
                "%imported%", String.valueOf(importedRows),
                "%existing%", String.valueOf(alreadyPresentRows),
                "%player_conflicts%", String.valueOf(playerConflictRows),
                "%qq_conflicts%", String.valueOf(qqConflictRows),
                "%invalid%", String.valueOf(invalidRows),
                "%non_qq%", String.valueOf(nonQqRows));
    }
}
