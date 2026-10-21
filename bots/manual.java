// Обычный Bot.move: вставьте код в редактор любого бота или загрузите этот файл.
// Клик по полю / координаты задают цель. Движение по прямой ступеньками, без обхода стен.
if (Boolean.TRUE.equals(mem.get("stopped"))) return Direction.NONE;

var target = (BotInput.Point) mem.get("target");
var start = (BotInput.Point) mem.get("start");
var direction = target == null ? Direction.NONE : api.towards(start, target);
if (direction == Direction.NONE || !api.canMoveTo(direction)) {
    target = api.askPoint(); // Блокирует ход бота, UI остаётся доступным.
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