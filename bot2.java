var dirs = new ArrayList<>(Arrays.asList(Direction.values()));
Collections.shuffle(dirs);

for (Direction dir : dirs) {
    if (api.next(dir) == 0) return dir;
}

for (Direction dir : dirs) {
    if (api.next(dir) == api.id()) return dir;
}
return Direction.UP;
