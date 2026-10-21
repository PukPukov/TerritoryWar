class Brain {
    static final Direction[] DIRS = Direction.values(); // 0:RIGHT, 1:DOWN, 2:LEFT, 3:UP
    static final int[] DX = {1, 0, -1, 0};
    static final int[] DY = {0, 1, 0, -1};
    static final int[] DIAG_X = {1, 1, -1, -1};
    static final int[] DIAG_Y = {1, -1, 1, -1};

    final int N = FIELD_SIZE;
    final int MAX_CELLS = N * N;
    final int myId;
    final int enemyId;
    final boolean flip; // false для верхнего-левого старта, true для нижнего-правого

    // Нормализованные направления относительно стартового угла
    final int FORWARD_X, FORWARD_Y, BACK_X, BACK_Y;
    final int startRX, startRY;

    // Структуры данных O(1) (выделяются один раз на 0-м тике)
    final int[] visitOrder = new int[MAX_CELLS];
    final byte[] parentDir = new byte[MAX_CELLS];
    final boolean[] onStack = new boolean[MAX_CELLS];
    final int[] frontierStack = new int[MAX_CELLS];
    final int[] wallRY = new int[N];
    final int[] wallPath = new int[N * 4];

    int stackSize = 0;
    int orderCounter = 0;
    int anchorOrder = 1;
    int wallLen = 0;
    int wallScanIdx = 0;

    // Состояние автомата
    int phase = 1; // 1: Якорь, 2: Главный разрез, 3: Захват и зачистка
    boolean cutComplete = false;
    boolean contestedMode = true;

    int lastDir = -1;
    int idleTicks = 0;

    // O(1) трекинг границ территории врага
    int minERX = N, maxERX = 0, minERY = N, maxERY = 0;

    Brain(BotAPI api) {
        this.myId = api.id();
        this.enemyId = (myId == 1) ? 2 : 1;
        this.flip = (api.x() >= N / 2);

        if (!flip) {
            FORWARD_X = 0; // RIGHT
            FORWARD_Y = 1; // DOWN
            BACK_X = 2;    // LEFT
            BACK_Y = 3;    // UP
        } else {
            FORWARD_X = 2; // LEFT
            FORWARD_Y = 3; // UP
            BACK_X = 0;    // RIGHT
            BACK_Y = 1;    // DOWN
        }

        this.startRX = rx(api.x());
        this.startRY = ry(api.y());
        Arrays.fill(wallRY, -1);
        Arrays.fill(parentDir, (byte) -1);
    }

    int rx(int x) { return flip ? (N - 1 - x) : x; }
    int ry(int y) { return flip ? (N - 1 - y) : y; }

    boolean inBounds(int x, int y) {
        return x >= 0 && x < N && y >= 0 && y < N;
    }

    // Проверка за O(1): принадлежит ли клетка нашему запечатанному Безопасному Резерву
    boolean isSafeReserve(int x, int y) {
        if (!cutComplete) return false;
        int rxx = rx(x);
        int w = wallRY[rxx];
        return w >= 0 && ry(y) < w;
    }

    boolean isValidEmpty(BotAPI api, int x, int y, boolean allowSafe) {
        if (!inBounds(x, y) || api.get(x, y) != 0) return false;
        return allowSafe || !isSafeReserve(x, y);
    }

    boolean hasValidEmptyNeighbor(BotAPI api, int x, int y, boolean allowSafe) {
        for (int d = 0; d < 4; d++) {
            if (isValidEmpty(api, x + DX[d], y + DY[d], allowSafe)) return true;
        }
        return false;
    }

    void pushFrontier(int idx) {
        if (!onStack[idx] && stackSize < MAX_CELLS) {
            onStack[idx] = true;
            frontierStack[stackSize++] = idx;
        }
    }

    Direction step(BotAPI api) {
        int x = api.x();
        int y = api.y();
        int idx = x * N + y;

        // 1. O(1) обновление информации о враге
        int ex = api.enemy().x();
        int ey = api.enemy().y();
        int erx = rx(ex);
        int ery = ry(ey);
        if (erx < minERX) minERX = erx;
        if (erx > maxERX) maxERX = erx;
        if (ery < minERY) minERY = ery;
        if (ery > maxERY) maxERY = ery;

        // 2. O(1) регистрация текущей клетки в дереве и стеке фронта
        int crx = rx(x);
        int cry = ry(y);

        if (visitOrder[idx] == 0) {
            visitOrder[idx] = ++orderCounter;
            if (lastDir != -1) {
                parentDir[idx] = (byte) ((lastDir + 2) & 3);
            }
            idleTicks = 0;

            if (!cutComplete) {
                if (cry > wallRY[crx]) wallRY[crx] = cry;
                if (wallLen < wallPath.length) wallPath[wallLen++] = idx;
            }
        } else {
            idleTicks++;
        }

        if (hasValidEmptyNeighbor(api, x, y, true)) {
            pushFrontier(idx);
        }

        // 3. Выбор хода в соответствии с текущей фазой
        int chosenDir = -1;

        if (phase == 1) {
            chosenDir = stepPhase1Anchor(api, x, y, crx, cry, idx);
        }
        if (phase == 2 && chosenDir == -1) {
            chosenDir = stepPhase2Cut(api, x, y, crx, cry, erx, ery);
        }
        if (phase == 3 || chosenDir == -1) {
            phase = 3;
            cutComplete = true;
            chosenDir = stepPhase3Conquer(api, x, y, idx, ex, ey);
        }

        // Страховочный фоллбэк (никогда не возвращает недопустимый ход)
        if (chosenDir < 0 || !api.canMoveTo(DIRS[chosenDir])) {
            for (int d = 0; d < 4; d++) {
                if (api.canMoveTo(DIRS[d])) {
                    chosenDir = d;
                    break;
                }
            }
            if (chosenDir < 0) chosenDir = 0;
        }

        lastDir = chosenDir;
        return DIRS[chosenDir];
    }

    // ФАЗА 1: Соединение стартовой точки с ближайшей стеной rx = 0
    int stepPhase1Anchor(BotAPI api, int x, int y, int crx, int cry, int idx) {
        if (crx > 0 && api.get(x + DX[BACK_X], y + DY[BACK_X]) == 0) {
            return BACK_X;
        }
        // Дошли до стены rx = 0: фиксируем якорь и шагаем на 1 клетку вперед (ry + 1)
        anchorOrder = visitOrder[idx];
        phase = 2;
        if (api.get(x + DX[FORWARD_Y], y + DY[FORWARD_Y]) == 0) {
            return FORWARD_Y;
        }
        return -1;
    }

    // ФАЗА 2: Динамическое разрезание карты от rx = 0 до противоположной границы
    int stepPhase2Cut(BotAPI api, int x, int y, int crx, int cry, int erx, int ery) {
        // Если достигли противоположной стены — разрез завершен!
        if (crx == N - 1 || cry == N - 1 || (cry == 0 && crx > startRX + 4)) {
            phase = 3;
            cutComplete = true;
            return -1;
        }

        boolean canFX = api.get(x + DX[FORWARD_X], y + DY[FORWARD_X]) == 0;
        boolean canFY = (cry < N - 2) && (api.get(x + DX[FORWARD_Y], y + DY[FORWARD_Y]) == 0);
        boolean canBY = (cry > 0) && (api.get(x + DX[BACK_Y], y + DY[BACK_Y]) == 0);

        // Сначала плотно проходим под собственным якорем до startRX + 4
        if (crx <= startRX + 4 && cry == startRY + 1) {
            if (canFX) return FORWARD_X;
        }

        // Если враг уже поднялся выше нас впереди — обходим его сверху или режем перед ним
        if (crx <= maxERX + 2 && minERY <= cry + 2) {
            if (minERX - crx > (cry - minERY) + 8 && minERY <= 20) {
                // Враг убежал вдоль дальней стены к самому верху — режем перед ним вниз
                if (canFY && crx >= minERX - 3) return FORWARD_Y;
                if (canFX && crx < minERX - 2) return FORWARD_X;
                if (canFY) return FORWARD_Y;
            }
            if (canBY && (minERX - crx) <= (cry - minERY) + 4) {
                return BACK_Y;
            }
            if (canFX) return FORWARD_X;
            if (canBY) return BACK_Y;
            if (canFY) return FORWARD_Y;
        } else {
            // Основной инвариант перехвата Phi = (ery - cry) - max(0, erx - crx)
            int phi = (ery - cry) - Math.max(0, erx - crx);
            boolean safeBelowEnemy = (crx > maxERX + 2)
                ? ((crx - erx) + (ery - cry) > 6)
                : (phi > 4 && cry < minERY - 3);

            if (safeBelowEnemy && canFY) {
                return FORWARD_Y;
            }
            if (canFX) return FORWARD_X;
            if (canFY) return FORWARD_Y;
            if (canBY) return BACK_Y;
        }

        // Если все передние направления заблокированы — переходим к Фазе 3
        phase = 3;
        cutComplete = true;
        return -1;
    }

    // ФАЗА 3: Захват спорной половины, обволакивание врага и быстрая зачистка
    int stepPhase3Conquer(BotAPI api, int x, int y, int idx, int ex, int ey) {
        // Очищаем верхушку стека от клеток, у которых не осталось допустимых пустых соседей (O(1) — до 16 проверок)
        cleanFrontierStack(api);

        // Защита от окончания игры по лимиту 128 холостых тиков:
        // если мы долго идем по своим клеткам, разрешаем 1 шаг в Резерв для сброса таймера
        boolean allowSafe = !contestedMode || (idleTicks >= 64);

        int bestDir = -1;
        int bestScore = Integer.MIN_VALUE;

        for (int d = 0; d < 4; d++) {
            int nx = x + DX[d];
            int ny = y + DY[d];
            if (!isValidEmpty(api, nx, ny, allowSafe)) continue;

            int score = evaluateEmptyCandidate(api, nx, ny, d, ex, ey, allowSafe);
            if (score > bestScore) {
                bestScore = score;
                bestDir = d;
            }
        }

        if (bestDir != -1) {
            return bestDir;
        }

        // Если в спорном режиме прямо под нами нет пустых спорных клеток —
        // идем к ближайшей активной точке фронта по кратчайшему пути в DAG
        return stepBacktrackShortcut(api, x, y, idx);
    }

    // O(1) эвристика оценки пустой клетки-кандидата
    int evaluateEmptyCandidate(BotAPI api, int nx, int ny, int dir, int ex, int ey, boolean allowSafe) {
        int emptyDeg = 0;
        int adjEnemy4 = 0;
        int adjBorder = 0;

        for (int d = 0; d < 4; d++) {
            int ax = nx + DX[d];
            int ay = ny + DY[d];
            int val = api.get(ax, ay);
            if (val == 0) {
                if (allowSafe || !isSafeReserve(ax, ay)) {
                    emptyDeg++;
                }
            } else if (val == enemyId) {
                adjEnemy4++;
            } else if (val == -1) {
                adjBorder++;
            }
        }

        int adjEnemy8 = 0;
        for (int d = 0; d < 4; d++) {
            int ax = nx + DIAG_X[d];
            int ay = ny + DIAG_Y[d];
            if (api.get(ax, ay) == enemyId) {
                adjEnemy8++;
            }
        }

        int score = 0;

        // 1. Никогда не заходим в 1-клеточный тупик (emptyDeg == 0), если есть проход дальше
        if (emptyDeg == 0) {
            score -= 10000;
        } else if (emptyDeg == 1) {
            score += 800; // Срезание углов и коридоров без оставления дыр
        } else if (emptyDeg == 2) {
            score += 600; // Движение вдоль стены (Гамильтонова спираль)
        } else {
            score += 400; // Выход в открытое пространство
        }

        // 2. Высший приоритет в боевом режиме — обволакивание (coating) территории врага
        if (adjEnemy4 > 0) {
            score += 4000 + adjEnemy4 * 200;
        } else if (adjEnemy8 > 0) {
            score += 2000 + adjEnemy8 * 100;
        }

        // Если мы рядом с активной головой врага — перехватываем и блокируем её
        int distEnemyHead = Math.abs(nx - ex) + Math.abs(ny - ey);
        if (distEnemyHead <= 6) {
            score += (8 - distEnemyHead) * 60;
        }

        // 3. В спорной зоне предпочитаем сначала обойти внешние границы карты, чтобы замкнуть контур
        if (contestedMode && adjBorder > 0 && emptyDeg >= 1) {
            score += 1200;
        }

        // 4. Бонус за сохранение направления (прямолинейность вдоль стен)
        if (dir == lastDir) {
            score += 50;
        }

        // Небольшой уклон в сторону базы врага при равенстве прочих факторов
        if (contestedMode) {
            score += (rx(nx) + ry(ny)) >> 4;
        }

        return score;
    }

    // O(1) очистка неактуальных вершин стека фронта (не более 16 итераций за тик)
    void cleanFrontierStack(BotAPI api) {
        int pops = 0;
        while (stackSize > 0 && pops < 16) {
            int topIdx = frontierStack[stackSize - 1];
            int tx = topIdx / N;
            int ty = topIdx % N;

            if (contestedMode) {
                // Если дошли по стеку до якоря (visitOrder <= anchorOrder) —
                // значит ВСЯ спорная территория на карте полностью захвачена!
                if (visitOrder[topIdx] <= anchorOrder) {
                    contestedMode = false;
                    break;
                }
                if (hasValidEmptyNeighbor(api, tx, ty, false)) {
                    break;
                }
            } else {
                if (hasValidEmptyNeighbor(api, tx, ty, true)) {
                    break;
                }
            }

            onStack[topIdx] = false;
            stackSize--;
            pops++;
        }

        if (contestedMode && stackSize == 0) {
            contestedMode = false;
        }
    }

    // O(1) выход из тупиков и спиралей напрямик через свои клетки по DAG visitOrder
    int stepBacktrackShortcut(BotAPI api, int x, int y, int idx) {
        int currOrder = visitOrder[idx];
        int targetOrder = 1;

        if (stackSize > 0) {
            int topIdx = frontierStack[stackSize - 1];
            targetOrder = visitOrder[topIdx];

            // Если мы уже стоим на целевой вершине (например, на якоре в момент переключения режима)
            if (idx == topIdx) {
                if (contestedMode && targetOrder <= anchorOrder) {
                    contestedMode = false;
                    for (int d = 0; d < 4; d++) {
                        if (isValidEmpty(api, x + DX[d], y + DY[d], true)) return d;
                    }
                }
                onStack[topIdx] = false;
                stackSize--;
            }
        }

        // Ищем соседа из нашей территории:
        // 1) Если сосед сам граничит с допустимой пустой клеткой — шагаем сразу к нему!
        for (int d = 0; d < 4; d++) {
            int nx = x + DX[d];
            int ny = y + DY[d];
            if (inBounds(nx, ny) && api.get(nx, ny) == myId) {
                if (hasValidEmptyNeighbor(api, nx, ny, !contestedMode)) {
                    return d;
                }
            }
        }

        // 2) Срезаем витки спирали напрямик: выбираем нашего соседа с минимальным visitOrder >= targetOrder
        if (currOrder > targetOrder) {
            int bestShortcutDir = -1;
            int minNeighborOrder = currOrder;

            for (int d = 0; d < 4; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                if (!inBounds(nx, ny) || api.get(nx, ny) != myId) continue;
                int nOrder = visitOrder[nx * N + ny];
                if (nOrder >= targetOrder && nOrder < minNeighborOrder) {
                    minNeighborOrder = nOrder;
                    bestShortcutDir = d;
                }
            }

            if (bestShortcutDir != -1) {
                return bestShortcutDir;
            }
        }

        // 3) Стандартный шаг по дереву родителей
        int pDir = parentDir[idx];
        if (pDir >= 0 && api.canMoveTo(DIRS[pDir])) {
            return pDir;
        }

        // 4) Если стек полностью пуст и мы вернулись к началу — проверяем вдоль главной стены оставшиеся карманы
        if (wallLen > 0) {
            wallScanIdx = (wallScanIdx + 1) % wallLen;
            int wIdx = wallPath[wallScanIdx];
            int wx = wIdx / N;
            int wy = wIdx % N;
            int bestD = -1;
            int bestDist = Integer.MAX_VALUE;
            for (int d = 0; d < 4; d++) {
                int nx = x + DX[d];
                int ny = y + DY[d];
                if (inBounds(nx, ny) && api.get(nx, ny) == myId) {
                    int dist = Math.abs(nx - wx) + Math.abs(ny - wy);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestD = d;
                    }
                }
            }
            if (bestD != -1) return bestD;
        }

        return 0;
    }
}

Brain brain = (Brain) mem.get("brain");
if (brain == null) {
    brain = new Brain(api);
    mem.put("brain", brain);
}
return brain.step(api);