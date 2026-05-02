package ai.jzhu.strategy.domain.model;

public record TradeSignal(
        int index,
        double price,
        Direction direction,
        String type,
        String reason
) {

    public static TradeSignal openLong(int index, double price, String reason) {
        return new TradeSignal(index, price, Direction.LONG, "OPEN", reason);
    }

    public static TradeSignal closeLong(int index, double price, String reason) {
        return new TradeSignal(index, price, Direction.LONG, "CLOSE", reason);
    }

    public static TradeSignal openShort(int index, double price, String reason) {
        return new TradeSignal(index, price, Direction.SHORT, "OPEN", reason);
    }

    public static TradeSignal closeShort(int index, double price, String reason) {
        return new TradeSignal(index, price, Direction.SHORT, "CLOSE", reason);
    }
}