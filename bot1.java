var dirs = Direction.values();
if (!mem.containsKey("dirIndex")) mem.put("dirIndex", 1);
int dirIndex = (int) mem.get("dirIndex");

Direction currDir = dirs[dirIndex];

if (api.next(currDir) != 0) {
    dirIndex = (dirIndex + 1) % 4;
    mem.put("dirIndex", dirIndex);
    currDir = dirs[dirIndex];
}

if (api.next(currDir) != 0) {
    for (Direction d : dirs) {
        if (api.next(d) == api.id()) return d;
    }
}
return currDir;
