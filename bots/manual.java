if (Boolean.TRUE.equals(mem.get("stopped"))) return Direction.NONE;

var target = (BotInput.Point) mem.get("target");
var start = (BotInput.Point) mem.get("start");
var direction = target == null ? Direction.NONE : api.towards(start, target);
if (direction == Direction.NONE || !api.canMoveTo(direction)) {
    target = api.askPoint();
    if (target == null) { // Отмена: стоять до сброса игры.
        mem.put("stopped", true);
        return Direction.NONE;
    }
    start = new BotInput.Point(api.x(), api.y());
    mem.put("start", start);
    mem.put("target", target);
    direction = api.towards(start, target);
}
return direction;