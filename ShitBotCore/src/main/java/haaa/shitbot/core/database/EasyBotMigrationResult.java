package haaa.shitbot.core.database;

/** Immutable summary returned after importing EasyBot binding records. */
public final class EasyBotMigrationResult {
    private final int totalRows;
    private final int qqRows;
    private final int importedRows;
    private final int alreadyPresentRows;
    private final int playerConflictRows;
    private final int qqConflictRows;
    private final int invalidRows;
    private final int nonQqRows;

    public EasyBotMigrationResult(int totalRows,
                                  int qqRows,
                                  int importedRows,
                                  int alreadyPresentRows,
                                  int playerConflictRows,
                                  int qqConflictRows,
                                  int invalidRows,
                                  int nonQqRows) {
        this.totalRows = totalRows;
        this.qqRows = qqRows;
        this.importedRows = importedRows;
        this.alreadyPresentRows = alreadyPresentRows;
        this.playerConflictRows = playerConflictRows;
        this.qqConflictRows = qqConflictRows;
        this.invalidRows = invalidRows;
        this.nonQqRows = nonQqRows;
    }

    public int getTotalRows() {
        return totalRows;
    }

    public int getQqRows() {
        return qqRows;
    }

    public int getImportedRows() {
        return importedRows;
    }

    public int getAlreadyPresentRows() {
        return alreadyPresentRows;
    }

    public int getPlayerConflictRows() {
        return playerConflictRows;
    }

    public int getQqConflictRows() {
        return qqConflictRows;
    }

    public int getInvalidRows() {
        return invalidRows;
    }

    public int getNonQqRows() {
        return nonQqRows;
    }

    public String describe() {
        return "读取=" + totalRows
                + ", QQ=" + qqRows
                + ", 导入=" + importedRows
                + ", 已存在=" + alreadyPresentRows
                + ", 玩家冲突=" + playerConflictRows
                + ", QQ冲突=" + qqConflictRows
                + ", 无效=" + invalidRows
                + ", 非QQ=" + nonQqRows;
    }
}
