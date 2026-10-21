/*
 * Territory Bot — для GameEngine из приложенного проекта Territory War.
 * ВСТАВИТЬ ВЕСЬ ФАЙЛ в поле алгоритма: это тело move, не отдельный класс.
 * Либо сохранить как bot1.java / bot2.java в рабочей папке игры.
 * Дополнительные import и зависимости не нужны.
 *
 * Стратегия: замыкать большие свободные прямоугольники, используя края карты.
 * Сетка 20x20 хранит след противника и уже защищённые области. Любая клетка
 * противника запрещает соответствующую макроклетку: оценка консервативна.
 * При вторжении план отменяется. Внутри замкнутой области врага нет, поэтому
 * финальный flood fill движка отдаст её нам без заполнения каждой клетки.
 *
 * После первого вызова — O(1) в худшем случае ПО РАЗМЕРУ КАРТЫ:
 *   - стратегическая сетка не больше 20x20;
 *   - не больше двух попыток выбора плана;
 *   - глобальный BFS: не больше 512 вершин за вызов, с сохранением очереди;
 *   - локальный BFS: не больше 289 вершин;
 *   - нет выделения массивов, очистки всей карты или восстановления всего пути.
 * На новом маршруте меняется метка epoch, а не очищается массив seen.
 * Инициализация и память: O(N^2), N = FIELD_SIZE; около 21*N*N байт + константа.
 * Финальная заливка самого GameEngine по-прежнему имеет сложность O(N^2).
 *
 * Используются только BotAPI и mem. Состояние изолировано между играми.
 * Подробности, воспроизводимые тесты и ограничения — в README.md архива.
 */
class Brain {
    final int n = FIELD_SIZE, k = Math.min(20, n), cells = n * n;
    final Direction[] dirs = {Direction.RIGHT, Direction.DOWN, Direction.LEFT, Direction.UP};
    final int[] dx = {1, 0, -1, 0}, dy = {0, 1, 0, -1};
    final boolean[] foe = new boolean[k * k], safe = new boolean[k * k];
    final boolean[] unreachable = new boolean[k * k];
    final int[] ep = new int[(k + 1) * (k + 1)], sp = new int[(k + 1) * (k + 1)];
    final long[] seen = new long[cells], visit = new long[cells];
    final byte[] parent = new byte[cells];
    final int[] queue = new int[cells];
    final int[] wx = new int[6], wy = new int[6];
    final int[] bestX = new int[6], bestY = new int[6];
    final int[] cx = new int[4], cy = new int[4];
    final int[] lx = new int[289], ly = new int[289], lf = new int[289], ld = new int[289], ls = new int[289];
    int bx0, bx1, by0, by1, wp, count, bestCount, ex, ey;
    int root = -1, head, tail, localToken;
    long epoch = 1, clock;
    boolean active;
    double bestScore;
    BotAPI a;
    int id, x, y;

    int edge(int i) { return (int)(((long)i * n + k - 1) / k); }
    int bin(int v) { return (int)((long)v * k / n); }
    int at(int px, int py) { return py * n + px; }
    int sum(int[] p, int x0, int y0, int x1, int y1) {
        int z = k + 1;
        return p[y1*z+x1]-p[y0*z+x1]-p[y1*z+x0]+p[y0*z+x0];
    }
    void prefixes() {
        for (int j = 0; j < k; ++j) {
            int e = 0, s = 0;
            for (int i = 0; i < k; ++i) {
                e += foe[j*k+i] || unreachable[j*k+i] ? 1 : 0;
                s += safe[j*k+i] ? 1 : 0;
                ep[(j+1)*(k+1)+i+1] = ep[j*(k+1)+i+1]+e;
                sp[(j+1)*(k+1)+i+1] = sp[j*(k+1)+i+1]+s;
            }
        }
    }
    void watchEnemy() {
        BotAPI enemy = a.enemy();
        ex = enemy.x(); ey = enemy.y();
        foe[bin(ey)*k+bin(ex)] = true;
        // Между вызовами соперник иногда делает два хода. Промежуточная
        // клетка всегда соседняя с текущей. Это учитывает и первый ход врага.
        for (int d=0; d<4; ++d) {
            int qx=ex+dx[d], qy=ey+dy[d];
            if (a.get(qx,qy)==3-id) foe[bin(qy)*k+bin(qx)]=true;
        }
        prefixes();
    }
    boolean boardEdge(int x0, int y0, int x1, int y1) {
        return x0==x1 && (x0==0 || x0==n-1) || y0==y1 && (y0==0 || y0==n-1);
    }
    // Проверяем восемь обходов прямоугольника. Стоимость включает путь
    // к началу стены. Приоритет: новая площадь / время, с поправкой на врага.
    void candidate(int c0, int r0, int c1, int r1) {
        int x0=edge(c0), x1=edge(c1)-1, y0=edge(r0), y1=edge(r1)-1;
        if (x1-x0<2 || y1-y0<2) return;
        int fresh=(c1-c0)*(r1-r0)-sum(sp,c0,r0,c1,r1);
        if (fresh<=0) return;
        double area=(double)fresh*n*n/(k*k);
        cx[0]=x0;cx[1]=x1;cx[2]=x1;cx[3]=x0;
        cy[0]=y0;cy[1]=y0;cy[2]=y1;cy[3]=y1;
        for(int s=0;s<4;++s) for(int rev=0;rev<2;++rev){
            int sign=rev==0?1:-1, start=s, end=4;
            // На границе карты не требуется рисовать стену.
            int skip=0;
            for(int z=0;z<4;++z){
                int u=(s+sign*z+8)%4,v=(u+sign+4)%4;
                if(!boardEdge(cx[u],cy[u],cx[v],cy[v]))break;
                skip++;
            }
            for(int z=3;z>=skip;--z){
                int u=(s+sign*z+8)%4,v=(u+sign+4)%4;
                if(!boardEdge(cx[u],cy[u],cx[v],cy[v]))break;
                end--;
            }
            if(skip==4)continue;
            start=(s+sign*skip+8)%4;
            int cost=Math.abs(x-cx[start])+Math.abs(y-cy[start]);
            for(int z=skip;z<end;++z){int u=(s+sign*z+8)%4,v=(u+sign+4)%4;cost+=Math.abs(cx[u]-cx[v])+Math.abs(cy[u]-cy[v]);}
            int gap=Math.max(Math.max(x0-ex,ex-x1),0)+Math.max(Math.max(y0-ey,ey-y1),0);
            double safety=0.65+Math.min(1.1,(double)gap/(0.20*cost+1));
            double score=area*safety/(cost+n*0.25+1);
            if(score>bestScore){
                bestScore=score;bestCount=0;
                for(int z=skip;z<=end;++z){int u=(s+sign*z+8)%4;bestX[bestCount]=cx[u];bestY[bestCount++]=cy[u];}
                bx0=c0;bx1=c1;by0=r0;by1=r1;
            }
        }
    }
    // Для каждой пары столбцов перечисляем максимальные свободные полосы.
    // Здесь O(k^3), но k <= 20 независимо от размера карты.
    boolean plan() {
        bestScore=0;bestCount=0;
        for(int l=0;l<k;++l)for(int r=l+1;r<=k;++r){
            int from=0;
            for(int row=0;row<=k;++row){
                if(row==k || sum(ep,l,row,r,row+1)>0){
                    if(row>from) candidate(l,from,r,row);
                    from=row+1;
                }
            }
        }
        if(bestCount==0)return false;
        count=bestCount;wp=0;
        for(int i=0;i<count;++i){wx[i]=bestX[i];wy[i]=bestY[i];}
        active=true;root=-1;
        return true;
    }
    // Обратный BFS от цели. parent хранит сразу следующий шаг к цели,
    // поэтому восстановление длинного маршрута в один тик не требуется.
    void search(int tx,int ty) {
        int target=at(tx,ty);
        if(root!=target){root=target;epoch++;head=0;tail=1;queue[0]=target;seen[target]=epoch;parent[target]=-1;}
        for(int budget=0;budget<512 && head<tail;++budget){
            int p=queue[head++],px=p%n,py=p/n;
            if(a.get(px,py)==3-id)continue;
            for(int d=0;d<4;++d){
                int qx=px+dx[d],qy=py+dy[d];
                int cell=a.get(qx,qy);
                if(cell<0 || cell==3-id)continue;
                int q=at(qx,qy);
                if(seen[q]!=epoch){seen[q]=epoch;parent[q]=(byte)(d^2);queue[tail++]=q;}
            }
        }
    }
    Direction travel(int tx,int ty) {
        if(a.get(tx,ty)==3-id){active=false;return explore();}
        search(tx,ty);
        int p=at(x,y);
        if(seen[p]==epoch && parent[p]>=0){
            int d=parent[p];
            if(a.canMoveTo(dirs[d]))return dirs[d];
            root=-1;
        }
        // Ограниченный локальный BFS: квадрат 17x17, не зависит от n.
        final int radius=8, side=17;
        // Счётчик локального BFS может переполниться: очищаем только 289 ячеек.
        if(++localToken==0){Arrays.fill(ls,0);localToken=1;}
        int h=0,t=1;lx[0]=0;ly[0]=0;lf[0]=-1;ld[0]=0;ls[radius*side+radius]=localToken;
        long best=Long.MAX_VALUE;int answer=-1;
        for(int budget=0;budget<289 && h<t;++budget){
            int i=h++,px=lx[i],py=ly[i];
            if(i>0){
                int cell=a.get(x+px,y+py),dist=Math.abs(x+px-tx)+Math.abs(y+py-ty);
                long score=(long)dist*1024+ld[i]*8+(cell==0?0:2);
                if(score<best){best=score;answer=lf[i];}
            }
            for(int d=0;d<4;++d){
                int qx=px+dx[d],qy=py+dy[d];
                if(Math.abs(qx)>radius || Math.abs(qy)>radius)continue;
                int q=(qy+radius)*side+qx+radius;
                if(ls[q]==localToken)continue;
                int cell=a.get(x+qx,y+qy);
                if(cell<0 || cell==3-id)continue;
                ls[q]=localToken;lx[t]=qx;ly[t]=qy;ld[t]=ld[i]+1;lf[t]=i==0?d:lf[i];t++;
            }
        }
        if(answer>=0 && best<(long)(Math.abs(x-tx)+Math.abs(y-ty))*1024)return dirs[answer];
        if(head>=tail && seen[p]!=epoch){
            // Враг только добавляет препятствия: недостижимая свободная
            // макроклетка уже никогда не станет достижимой.
            unreachable[bin(ty)*k+bin(tx)]=true;
            active=false;root=-1;
        }
        return explore();
    }
    Direction explore() {
        long best=Long.MIN_VALUE;int ans=-1;
        for(int d=0;d<4;++d){
            int qx=x+dx[d],qy=y+dy[d],v=a.get(qx,qy);
            if(v<0 || v==3-id)continue;
            long score=(v==0?(safe[bin(qy)*k+bin(qx)]?1L<<50:1L<<52):0)-visit[at(qx,qy)];
            if(score>best){best=score;ans=d;}
        }
        return ans<0?null:dirs[ans];
    }
    Direction step(BotAPI api0){
        a=api0;id=a.id();x=a.x();y=a.y();clock++;visit[at(x,y)]=clock;
        watchEnemy();
        if(active && sum(ep,bx0,by0,bx1,by1)>0)active=false;
        for(int attempt=0;attempt<2;++attempt){
            if(!active && !plan())return explore();
            for(int i=0;i<6 && wp<count && x==wx[wp] && y==wy[wp];++i)wp++;
            if(wp==count){
                for(int row=by0;row<by1;++row)for(int col=bx0;col<bx1;++col)safe[row*k+col]=true;
                prefixes();active=false;continue;
            }
            // К началу стены разрешены обходы. Саму стену рисуем только
            // прямыми отрезками: иначе нельзя считать прямоугольник замкнутым.
            if(wp==0)return travel(wx[wp],wy[wp]);
            int d=x<wx[wp]?0:y<wy[wp]?1:x>wx[wp]?2:3;
            if(a.canMoveTo(dirs[d]))return dirs[d];
            active=false;
        }
        return explore();
    }
}
Brain brain=(Brain)mem.get("enclosure-brain");
if(brain==null){brain=new Brain();mem.put("enclosure-brain",brain);}
return brain.step(api);
