class methods {
    
    public static Direction dirSmart(BotAPI api, int dirIndex, Direction[] dirs, int[][] visits) {
        int leastVisitedVisits = Integer.MAX_VALUE;
        Direction leastVisitedDir = null;
        int spreader = api.tickCount() / 133;
        int snipeTicks = ((int) (((double) FIELD_SIZE) * 0.8));
        snipe: if (api.tickCount() < snipeTicks) {
            return api.id() == 1 ?
                (api.tickCount() % 2 == 0 ? Direction.DOWN : Direction.RIGHT) :
                (api.tickCount() % 2 == 0 ? Direction.UP   : Direction.LEFT);
        }
        for (int count = 0; count < 4; count++) {
            Direction dir = dirs[(spreader+dirIndex+((api.id() == 1 ? 2 : 4)-count))%4];
            int next = api.next(dir);
            if (!api.canMoveTo(dir)) continue;
            if (next == 0 && (ThreadLocalRandom.current().nextDouble() > 0.02)) return dir;
            int iVisits = visits[api.nextX(dir)][api.nextY(dir)];
            if (iVisits < leastVisitedVisits) {
                leastVisitedVisits = iVisits;
                leastVisitedDir = dir;
            }
        }
        if (leastVisitedDir == null) return Direction.RIGHT;
        return leastVisitedDir;
    }
};

int[][]   visits   = (int[][]) mem.computeIfAbsent("visits",   _ -> new int[FIELD_SIZE][FIELD_SIZE]);
int       dirIndex = (int)     mem.computeIfAbsent("dirIndex", _ -> 1);
Direction[] dirs   = Direction.values();
Direction currDir  = dirs[dirIndex];
visits[api.x()][api.y()]++;
return methods.dirSmart(api, dirIndex, dirs, visits);