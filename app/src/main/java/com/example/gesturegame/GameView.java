package com.example.gesturegame;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class GameView extends SurfaceView implements SurfaceHolder.Callback {

    // ══════════════════════════════════════════════════════════════
    //  INNER CLASSES
    // ══════════════════════════════════════════════════════════════

    /** A living enemy that walks toward the player and fires bullets. */
    static class Enemy {
        float x, y;
        final float width  = 70f;
        final float height = 90f;
        int   health = 2;
        boolean alive = true;
        long lastShotTime = 0;
        static final long SHOOT_INTERVAL_MS = 2500L;

        Enemy(float x, float y) {
            this.x = x;
            this.y = y;
        }

        RectF getBounds() {
            return new RectF(x, y, x + width, y + height);
        }
    }

    /** A road-stripe that scrolls from right to left. */
    static class Stripe {
        float x;
        final float y;
        final float w = 70f;
        final float h = 10f;

        Stripe(float x, float y) { this.x = x; this.y = y; }
    }

    // ══════════════════════════════════════════════════════════════
    //  SCREEN / LAYOUT
    // ══════════════════════════════════════════════════════════════
    private int   screenWidth, screenHeight;
    private float groundY;

    // ══════════════════════════════════════════════════════════════
    //  PLAYER
    // ══════════════════════════════════════════════════════════════
    private float playerX, playerY;
    private final float playerWidth  = 80f;
    private final float playerHeight = 100f;
    private float   playerVelocityY  = 0f;
    private boolean isJumping        = false;

    // ══════════════════════════════════════════════════════════════
    //  LASER (ATTACK)
    // ══════════════════════════════════════════════════════════════
    private boolean laserActive    = false;
    private float   laserFuel      = 5000f;   // milliseconds of fuel
    private static final float LASER_MAX    = 5000f;
    private boolean laserRefilling = false;

    // ══════════════════════════════════════════════════════════════
    //  BLOCK
    // ══════════════════════════════════════════════════════════════
    private boolean isBlocking = false;

    // ══════════════════════════════════════════════════════════════
    //  SPECIAL MOVE
    // ══════════════════════════════════════════════════════════════
    private boolean specialActive        = false;
    private boolean specialReady         = false;
    private long    specialFirstUsedTime = -1L;
    private long    specialLastUsedTime  = -1L;
    private int     specialTimer         = 0;
    private static final long SPECIAL_COOLDOWN_MS = 20_000L;

    // ══════════════════════════════════════════════════════════════
    //  WORLD / ROAD
    // ══════════════════════════════════════════════════════════════
    private float worldSpeed = 9f;
    private final List<Stripe>  stripes   = new ArrayList<>();
    private final List<RectF>   obstacles = new ArrayList<>();
    private long lastObstacleTime = 0L;

    // ══════════════════════════════════════════════════════════════
    //  ENEMIES & BULLETS
    // ══════════════════════════════════════════════════════════════
    private final List<Enemy> enemies      = new ArrayList<>();
    private final List<RectF> enemyBullets = new ArrayList<>();
    private long lastEnemyTime = 0L;

    // ══════════════════════════════════════════════════════════════
    //  SCORE & STATE
    // ══════════════════════════════════════════════════════════════
    private int     score    = 0;
    private boolean gameOver = false;

    // ══════════════════════════════════════════════════════════════
    //  PHYSICS
    // ══════════════════════════════════════════════════════════════
    private static final float GRAVITY    =  1.4f;

    private static final float JUMP_FORCE = -32f;

    // ══════════════════════════════════════════════════════════════
    //  GESTURE INPUT
    // ══════════════════════════════════════════════════════════════
    private volatile String currentGesture = "None";

    // ══════════════════════════════════════════════════════════════
    //  TIMING
    // ══════════════════════════════════════════════════════════════
    private long lastFrameTime = System.currentTimeMillis();

    // ══════════════════════════════════════════════════════════════
    //  GAME THREAD
    // ══════════════════════════════════════════════════════════════
    private GameThread gameThread;

    // ══════════════════════════════════════════════════════════════
    //  ACTIVITY REFERENCE
    // ══════════════════════════════════════════════════════════════
    private GameActivity gameActivity;

    // ══════════════════════════════════════════════════════════════
    //  CONSTRUCTOR
    // ══════════════════════════════════════════════════════════════
    public GameView(Context context) {
        super(context);
        getHolder().addCallback(this);
        setFocusable(true);
        if (context instanceof GameActivity) {
            gameActivity = (GameActivity) context;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SURFACE CALLBACKS
    // ══════════════════════════════════════════════════════════════
    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        screenWidth  = getWidth();
        screenHeight = getHeight();
        groundY      = screenHeight * 0.75f;
        playerX      = screenWidth  * 0.18f;
        playerY      = groundY - playerHeight;

        initRoad();
        lastFrameTime = System.currentTimeMillis();

        gameThread = new GameThread(getHolder(), this);
        gameThread.setRunning(true);
        gameThread.start();
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        screenWidth  = w;
        screenHeight = h;
        groundY      = screenHeight * 0.75f;
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        pause();
    }

    // ══════════════════════════════════════════════════════════════
    //  GESTURE INPUT
    // ══════════════════════════════════════════════════════════════
    public void onGestureReceived(String gesture) {
        this.currentGesture = gesture;
    }

    // ══════════════════════════════════════════════════════════════
    //  ROAD INITIALISATION
    // ══════════════════════════════════════════════════════════════
    private void initRoad() {
        stripes.clear();
        float stripeY  = groundY + (screenHeight - groundY) * 0.45f;
        float gapX     = screenWidth / 8f;
        for (int i = 0; i < 10; i++) {
            stripes.add(new Stripe(i * (70f + gapX), stripeY));
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  MASTER UPDATE (called every frame by GameThread)
    // ══════════════════════════════════════════════════════════════
    public void update() {
        if (gameOver || screenWidth == 0) return;

        long now     = System.currentTimeMillis();
        long deltaMs = Math.min(now - lastFrameTime, 50L); // cap at 50 ms
        lastFrameTime = now;

        handleGestureInput();
        applyPhysics();
        updateRoad();
        spawnObstacles(now);
        updateObstacles();
        spawnEnemies(now);
        updateEnemies(now);
        updateEnemyBullets();
        updateLaser(deltaMs);
        updateSpecial(now);
    }

    // ══════════════════════════════════════════════════════════════
    //  GESTURE → ACTION MAPPING
    // ══════════════════════════════════════════════════════════════
    private void handleGestureInput() {
        if (gameOver) return;

        // Reset per-frame states
        isBlocking  = false;
        laserActive = false;

        switch (currentGesture) {

            case "Peace Sign":          // ── JUMP ──
                if (!isJumping) {
                    playerVelocityY = JUMP_FORCE;
                    isJumping = true;
                }
                break;

            case "Fist":               // ── LASER ATTACK ──
                if (laserFuel > 0 && !laserRefilling) {
                    laserActive = true;
                }
                break;

            case "Open Palm":          // ── BLOCK ──
                isBlocking = true;
                break;

            case "Thumbs Up":          // ── SPECIAL ──
                activateSpecial();
                break;

            default:
                break;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  PHYSICS
    // ══════════════════════════════════════════════════════════════
    private void applyPhysics() {
        playerVelocityY += GRAVITY;
        playerY         += playerVelocityY;
        if (playerY >= groundY - playerHeight) {
            playerY         = groundY - playerHeight;
            playerVelocityY = 0f;
            isJumping       = false;
        }
    }

    private RectF getPlayerBounds() {
        return new RectF(
                playerX + 10,
                playerY + 10,
                playerX + playerWidth  - 10,
                playerY + playerHeight - 10);
    }

    private RectF getShieldBounds() {
        float cx = playerX + playerWidth  / 2f;
        float cy = playerY + playerHeight / 2f;
        return new RectF(cx - 80f, cy - 80f, cx + 80f, cy + 80f);
    }

    // ══════════════════════════════════════════════════════════════
    //  ROAD UPDATE
    // ══════════════════════════════════════════════════════════════
    private void updateRoad() {
        if (worldSpeed < 20f) worldSpeed += 0.003f;

        float totalW = 70f + screenWidth / 8f;
        for (Stripe s : stripes) {
            s.x -= worldSpeed;
            if (s.x + s.w < 0) s.x += stripes.size() * totalW;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  OBSTACLE SPAWNING & UPDATE
    // ══════════════════════════════════════════════════════════════
    private void spawnObstacles(long now) {
        if (now - lastObstacleTime > 2800) {
            lastObstacleTime = now;
            float h = 45f + (float)(Math.random() * 60f);
            float w = 48f + (float)(Math.random() * 44f);
            obstacles.add(new RectF(
                    screenWidth,
                    groundY - h,
                    screenWidth + w,
                    groundY));
        }
    }

    private void updateObstacles() {
        Iterator<RectF> it = obstacles.iterator();
        while (it.hasNext()) {
            RectF obs = it.next();
            obs.left  -= worldSpeed;
            obs.right -= worldSpeed;
            if (obs.right < 0) { it.remove(); continue; }
            if (RectF.intersects(obs, getPlayerBounds())) {
                triggerGameOver();
                return;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  ENEMY SPAWNING & UPDATE
    // ══════════════════════════════════════════════════════════════
    private void spawnEnemies(long now) {
        if (now - lastEnemyTime > 3500) {
            lastEnemyTime = now;
            enemies.add(new Enemy(screenWidth + 60f, groundY - 90f));
        }
    }

    private void updateEnemies(long now) {
        Iterator<Enemy> it = enemies.iterator();
        while (it.hasNext()) {
            Enemy e = it.next();
            e.x -= worldSpeed * 0.55f;

            if (e.x + e.width < 0) { it.remove(); continue; }

            // Shoot bullet toward player
            if (e.alive && now - e.lastShotTime > Enemy.SHOOT_INTERVAL_MS) {
                e.lastShotTime = now;
                float by = e.y + e.height / 2f;
                enemyBullets.add(new RectF(e.x - 4f, by - 7f, e.x + 12f, by + 7f));
            }

            // Enemy body collision with player
            if (e.alive && RectF.intersects(e.getBounds(), getPlayerBounds())) {
                triggerGameOver();
                return;
            }
        }
        enemies.removeIf(e -> !e.alive);
    }

    // ══════════════════════════════════════════════════════════════
    //  ENEMY BULLETS UPDATE
    // ══════════════════════════════════════════════════════════════
    private void updateEnemyBullets() {
        Iterator<RectF> it = enemyBullets.iterator();
        while (it.hasNext()) {
            RectF b = it.next();
            b.left  -= 13f;
            b.right -= 13f;

            if (b.right < 0) { it.remove(); continue; }

            // Block intercepts bullet
            if (isBlocking && RectF.intersects(b, getShieldBounds())) {
                it.remove();
                continue;
            }

            // Bullet hits player → game over
            if (!gameOver && RectF.intersects(b, getPlayerBounds())) {
                it.remove();
                triggerGameOver();
                return;
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  LASER UPDATE
    // ══════════════════════════════════════════════════════════════
    private void updateLaser(long deltaMs) {
        if (laserActive) {
            laserFuel -= deltaMs;
            if (laserFuel <= 0f) {
                laserFuel      = 0f;
                laserActive    = false;
                laserRefilling = true;
            } else {
                hitEnemiesWithLaser(1);
            }
        } else if (laserRefilling) {
            laserFuel += deltaMs;
            if (laserFuel >= LASER_MAX) {
                laserFuel      = LASER_MAX;
                laserRefilling = false;
            }
        }
    }

    /** Deal `damage` to every enemy intersecting the laser ray. */
    private void hitEnemiesWithLaser(int damage) {
        float laserY = playerY + playerHeight / 2f;
        for (Enemy e : enemies) {
            if (!e.alive) continue;
            if (e.x > playerX
                    && e.y < laserY + 8f
                    && e.y + e.height > laserY - 8f) {
                e.health -= damage;
                if (e.health <= 0) {
                    e.alive = false;
                    score++;
                    if (gameActivity != null) gameActivity.updateScore(score);
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  SPECIAL MOVE
    // ══════════════════════════════════════════════════════════════
    private void activateSpecial() {
        long now = System.currentTimeMillis();
        if (specialFirstUsedTime == -1L) specialFirstUsedTime = now;

        long sinceLastUse = (specialLastUsedTime == -1L)
                ? Long.MAX_VALUE
                : now - specialLastUsedTime;

        if (sinceLastUse < SPECIAL_COOLDOWN_MS) return;  // still on cooldown

        specialLastUsedTime = now;
        specialReady        = false;
        specialActive       = true;
        specialTimer        = 50;

        // 1.5× damage to all on-screen enemies
        for (Enemy e : enemies) {
            if (!e.alive) continue;
            e.health -= 2; // ceil(1 * 1.5) = 2 — kills in one special hit
            if (e.health <= 0) {
                e.alive = false;
                score++;
                if (gameActivity != null) gameActivity.updateScore(score);
            }
        }
        enemies.removeIf(e -> !e.alive);
    }

    private void updateSpecial(long now) {
        if (specialActive) {
            specialTimer--;
            if (specialTimer <= 0) specialActive = false;
        }

        // Determine if cooldown has elapsed → special is ready again
        if (specialFirstUsedTime != -1L && !specialActive) {
            long reference = (specialLastUsedTime == -1L)
                    ? specialFirstUsedTime
                    : specialLastUsedTime;
            specialReady = (now - reference) >= SPECIAL_COOLDOWN_MS;
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GAME OVER
    // ══════════════════════════════════════════════════════════════
    private void triggerGameOver() {
        if (gameOver) return;
        gameOver = true;
        if (gameThread != null) gameThread.setRunning(false);
    }

    // ══════════════════════════════════════════════════════════════
    //  MASTER DRAW
    // ══════════════════════════════════════════════════════════════
    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        if (canvas == null) return;

        drawRoad(canvas);
        drawObstacles(canvas);
        drawEnemyBullets(canvas);
        drawEnemies(canvas);
        drawLaser(canvas);
        drawPlayer(canvas);
        drawSpecialEffect(canvas);
        drawFuelBar(canvas);
        drawSpecialIndicator(canvas);

        if (gameOver) drawGameOver(canvas);
    }

    // ══════════════════════════════════════════════════════════════
    //  DRAW — ROAD
    // ══════════════════════════════════════════════════════════════
    private void drawRoad(Canvas canvas) {
        // Sky gradient
        Paint sky = new Paint();
        sky.setColor(Color.parseColor("#0d0d1a"));
        canvas.drawRect(0, 0, screenWidth, groundY, sky);

        // Distant glow on horizon
        Paint horizonGlow = new Paint();
        horizonGlow.setShader(new RadialGradient(
                screenWidth / 2f, groundY,
                screenWidth * 0.6f,
                Color.parseColor("#33e94560"),
                Color.TRANSPARENT,
                Shader.TileMode.CLAMP));
        canvas.drawRect(0, groundY - 80f, screenWidth, groundY + 10f, horizonGlow);

        // Road surface
        Paint road = new Paint();
        road.setColor(Color.parseColor("#1e1e1e"));
        canvas.drawRect(0, groundY, screenWidth, screenHeight, road);

        // Road edge (red)
        Paint edge = new Paint();
        edge.setColor(Color.parseColor("#e94560"));
        edge.setStrokeWidth(5f);
        canvas.drawLine(0, groundY, screenWidth, groundY, edge);

        // Road stripes
        Paint stripe = new Paint();
        stripe.setColor(Color.parseColor("#dddddd"));
        stripe.setAntiAlias(true);
        for (Stripe s : stripes) {
            canvas.drawRoundRect(
                    new RectF(s.x, s.y - s.h / 2f, s.x + s.w, s.y + s.h / 2f),
                    4, 4, stripe);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  DRAW — OBSTACLES
    // ══════════════════════════════════════════════════════════════
    private void drawObstacles(Canvas canvas) {
        Paint body = new Paint();
        body.setColor(Color.parseColor("#ff6b35"));
        body.setAntiAlias(true);

        Paint xPaint = new Paint();
        xPaint.setColor(Color.WHITE);
        xPaint.setStrokeWidth(4f);
        xPaint.setAntiAlias(true);

        Paint shadow = new Paint();
        shadow.setColor(Color.parseColor("#66000000"));

        for (RectF obs : obstacles) {
            // Shadow
            canvas.drawRect(obs.left + 6, obs.top + 6, obs.right + 6, obs.bottom + 6, shadow);
            // Body
            canvas.drawRoundRect(obs, 8, 8, body);
            // X mark
            canvas.drawLine(obs.left + 8, obs.top + 8, obs.right - 8, obs.bottom - 8, xPaint);
            canvas.drawLine(obs.right - 8, obs.top + 8, obs.left + 8, obs.bottom - 8, xPaint);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  DRAW — ENEMIES
    // ══════════════════════════════════════════════════════════════
    private void drawEnemies(Canvas canvas) {
        for (Enemy e : enemies) {
            if (!e.alive) continue;

            float ex = e.x, ey = e.y, ew = e.width, eh = e.height;

            // Body
            Paint body = new Paint();
            body.setColor(Color.parseColor("#7b2d8b"));
            body.setAntiAlias(true);
            canvas.drawRoundRect(new RectF(ex, ey, ex + ew, ey + eh), 12, 12, body);

            // Spikes on top
            Paint spike = new Paint();
            spike.setColor(Color.parseColor("#9b3dab"));
            spike.setAntiAlias(true);
            for (int i = 0; i < 3; i++) {
                float sx = ex + 12f + i * 22f;
                android.graphics.Path path = new android.graphics.Path();
                path.moveTo(sx, ey - 18f);
                path.lineTo(sx - 8f, ey);
                path.lineTo(sx + 8f, ey);
                path.close();
                canvas.drawPath(path, spike);
            }

            // Eyes (glowing red)
            Paint eyeWhite = new Paint();
            eyeWhite.setColor(Color.WHITE);
            eyeWhite.setAntiAlias(true);
            canvas.drawCircle(ex + 18f, ey + 30f, 10f, eyeWhite);
            canvas.drawCircle(ex + 50f, ey + 30f, 10f, eyeWhite);

            Paint pupil = new Paint();
            pupil.setColor(Color.parseColor("#ff0000"));
            pupil.setAntiAlias(true);
            canvas.drawCircle(ex + 18f, ey + 30f, 5f, pupil);
            canvas.drawCircle(ex + 50f, ey + 30f, 5f, pupil);

            // Mouth (jagged line)
            Paint mouth = new Paint();
            mouth.setColor(Color.parseColor("#ff4444"));
            mouth.setStrokeWidth(3f);
            mouth.setAntiAlias(true);
            canvas.drawLine(ex + 16f, ey + 60f, ex + 26f, ey + 68f, mouth);
            canvas.drawLine(ex + 26f, ey + 68f, ex + 36f, ey + 58f, mouth);
            canvas.drawLine(ex + 36f, ey + 58f, ex + 46f, ey + 66f, mouth);
            canvas.drawLine(ex + 46f, ey + 66f, ex + 56f, ey + 60f, mouth);

            // Health bar background
            Paint hbBg = new Paint();
            hbBg.setColor(Color.parseColor("#44ffffff"));
            canvas.drawRoundRect(
                    new RectF(ex, ey - 18f, ex + ew, ey - 8f),
                    4, 4, hbBg);

            // Health bar fill
            Paint hbFg = new Paint();
            hbFg.setColor(Color.parseColor("#00ff88"));
            float ratio = Math.max(0f, e.health / 2f);
            canvas.drawRoundRect(
                    new RectF(ex, ey - 18f, ex + ew * ratio, ey - 8f),
                    4, 4, hbFg);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  DRAW — ENEMY BULLETS
    // ══════════════════════════════════════════════════════════════
    private void drawEnemyBullets(Canvas canvas) {
        Paint p = new Paint();
        p.setColor(Color.parseColor("#ff3333"));
        p.setAntiAlias(true);

        Paint glow = new Paint();
        glow.setColor(Color.parseColor("#66ff3333"));
        glow.setAntiAlias(true);

        for (RectF b : enemyBullets) {
            float cx = b.centerX(), cy = b.centerY();
            canvas.drawOval(new RectF(cx - 14f, cy - 14f, cx + 14f, cy + 14f), glow);
            canvas.drawOval(b, p);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  DRAW — LASER
    // ══════════════════════════════════════════════════════════════
    private void drawLaser(Canvas canvas) {
        if (!laserActive) return;

        float laserY = playerY + playerHeight / 2f;
        float startX = playerX + playerWidth;

        // Outer glow
        Paint glow = new Paint();
        glow.setColor(Color.parseColor("#2200ffff"));
        glow.setStrokeWidth(30f);
        glow.setAntiAlias(true);
        canvas.drawLine(startX, laserY, screenWidth, laserY, glow);

        // Mid
        Paint mid = new Paint();
        mid.setColor(Color.parseColor("#8800ffff"));
        mid.setStrokeWidth(10f);
        mid.setAntiAlias(true);
        canvas.drawLine(startX, laserY, screenWidth, laserY, mid);

        // Core (white-hot)
        Paint core = new Paint();
        core.setColor(Color.WHITE);
        core.setStrokeWidth(3f);
        core.setAntiAlias(true);
        canvas.drawLine(startX, laserY, screenWidth, laserY, core);
    }

    // ══════════════════════════════════════════════════════════════
    //  DRAW — FUEL BAR
    // ══════════════════════════════════════════════════════════════
    private void drawFuelBar(Canvas canvas) {
        float barW = 200f, barH = 16f;
        float bx   = 20f,  by  = screenHeight - 70f;

        // Background
        Paint bg = new Paint();
        bg.setColor(Color.parseColor("#88000000"));
        canvas.drawRoundRect(new RectF(bx, by, bx + barW, by + barH), 8, 8, bg);

        // Fill
        Paint fg = new Paint();
        fg.setColor(laserRefilling
                ? Color.parseColor("#666666")
                : Color.parseColor("#00ffff"));
        float ratio = laserFuel / LASER_MAX;
        if (ratio > 0.001f) {
            canvas.drawRoundRect(
                    new RectF(bx, by, bx + barW * ratio, by + barH),
                    8, 8, fg);
        }

        // Label
        Paint label = new Paint();
        label.setColor(Color.WHITE);
        label.setTextSize(18f);
        label.setAntiAlias(true);
        canvas.drawText(
                laserRefilling ? "RECHARGING..." : "⚡ LASER FUEL",
                bx, by - 5f, label);
    }

    // ══════════════════════════════════════════════════════════════
    //  DRAW — PLAYER
    // ══════════════════════════════════════════════════════════════
    private void drawPlayer(Canvas canvas) {
        float px = playerX, py = playerY;
        float pw = playerWidth, ph = playerHeight;

        // Body color based on state
        int bodyColor;
        if (isBlocking)       bodyColor = Color.parseColor("#0f3460");   // deep blue
        else if (laserActive) bodyColor = Color.parseColor("#00cccc");   // cyan
        else if (isJumping)   bodyColor = Color.parseColor("#533483");   // purple
        else                  bodyColor = Color.parseColor("#e94560");   // red (idle)

        // Shadow
        Paint shadow = new Paint();
        shadow.setColor(Color.parseColor("#44000000"));
        shadow.setAntiAlias(true);
        canvas.drawOval(new RectF(px + 5f, groundY - 8f, px + pw - 5f, groundY + 8f), shadow);

        // Body
        Paint body = new Paint();
        body.setColor(bodyColor);
        body.setAntiAlias(true);
        canvas.drawRoundRect(new RectF(px, py, px + pw, py + ph), 14, 14, body);

        // Head
        Paint head = new Paint();
        head.setColor(Color.parseColor("#f5a623"));
        head.setAntiAlias(true);
        float hcx = px + pw / 2f, hcy = py - 28f;
        canvas.drawCircle(hcx, hcy, 26f, head);

        // Eyes
        Paint eyeW = new Paint();
        eyeW.setColor(Color.WHITE);
        eyeW.setAntiAlias(true);
        canvas.drawCircle(hcx - 9f, hcy - 5f, 7f, eyeW);
        canvas.drawCircle(hcx + 9f, hcy - 5f, 7f, eyeW);

        Paint pupil = new Paint();
        pupil.setColor(Color.BLACK);
        canvas.drawCircle(hcx - 9f, hcy - 5f, 3.5f, pupil);
        canvas.drawCircle(hcx + 9f, hcy - 5f, 3.5f, pupil);

        // State label above head
        String stateLabel = "";
        if (isBlocking)       stateLabel = "🛡 BLOCK";
        else if (laserActive) stateLabel = "⚡ LASER";
        else if (isJumping)   stateLabel = "↑ JUMP";
        else if (specialActive) stateLabel = "★ SPECIAL!";

        if (!stateLabel.isEmpty()) {
            Paint lbl = new Paint();
            lbl.setColor(Color.WHITE);
            lbl.setTextSize(22f);
            lbl.setTextAlign(Paint.Align.CENTER);
            lbl.setFakeBoldText(true);
            lbl.setAntiAlias(true);
            canvas.drawText(stateLabel, hcx, hcy - 40f, lbl);
        }

        // Block shield ring
        if (isBlocking) {
            Paint shield = new Paint();
            shield.setColor(Color.parseColor("#00b4d8"));
            shield.setAlpha(160);
            shield.setStyle(Paint.Style.STROKE);
            shield.setStrokeWidth(7f);
            shield.setAntiAlias(true);
            float cx = px + pw / 2f, cy = py + ph / 2f;
            canvas.drawCircle(cx, cy, 80f, shield);

            // Inner shimmer
            shield.setAlpha(60);
            shield.setStrokeWidth(20f);
            canvas.drawCircle(cx, cy, 80f, shield);
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  DRAW — SPECIAL EFFECT (screen flash)
    // ══════════════════════════════════════════════════════════════
    private void drawSpecialEffect(Canvas canvas) {
        if (!specialActive) return;
        float alpha = (float) specialTimer / 50f;

        Paint flash = new Paint();
        flash.setColor(Color.parseColor("#ffcc00"));
        flash.setAlpha((int)(100 * alpha));
        canvas.drawRect(0, 0, screenWidth, screenHeight, flash);

        Paint text = new Paint();
        text.setColor(Color.parseColor("#ffcc00"));
        text.setTextSize(64f);
        text.setTextAlign(Paint.Align.CENTER);
        text.setFakeBoldText(true);
        text.setAlpha((int)(255 * alpha));
        text.setAntiAlias(true);
        canvas.drawText("★ SPECIAL ★",
                screenWidth / 2f, screenHeight / 3f, text);
    }

    // ══════════════════════════════════════════════════════════════
    //  DRAW — SPECIAL READY INDICATOR (right edge badge)
    // ══════════════════════════════════════════════════════════════
    private void drawSpecialIndicator(Canvas canvas) {
        if (!specialReady || specialActive) return;

        // Pulsing badge — pulse based on time
        float pulse = (float)(0.85 + 0.15 * Math.sin(
                System.currentTimeMillis() / 300.0));

        float cx = screenWidth - 48f;
        float cy = screenHeight / 2f;
        float r  = 42f * pulse;

        // Outer glow
        Paint glow = new Paint();
        glow.setColor(Color.parseColor("#66ffcc00"));
        glow.setAntiAlias(true);
        canvas.drawCircle(cx, cy, r + 14f, glow);

        // Badge circle
        Paint badge = new Paint();
        badge.setColor(Color.parseColor("#ffcc00"));
        badge.setAntiAlias(true);
        canvas.drawCircle(cx, cy, r, badge);

        // Star
        Paint star = new Paint();
        star.setColor(Color.BLACK);
        star.setTextSize(28f * pulse);
        star.setTextAlign(Paint.Align.CENTER);
        star.setFakeBoldText(true);
        star.setAntiAlias(true);
        canvas.drawText("★", cx, cy - 8f, star);

        Paint ready = new Paint();
        ready.setColor(Color.BLACK);
        ready.setTextSize(14f);
        ready.setTextAlign(Paint.Align.CENTER);
        ready.setFakeBoldText(true);
        ready.setAntiAlias(true);
        canvas.drawText("READY", cx, cy + 14f, ready);
    }

    // ══════════════════════════════════════════════════════════════
    //  DRAW — GAME OVER SCREEN
    // ══════════════════════════════════════════════════════════════
    private void drawGameOver(Canvas canvas) {
        // Dark overlay
        Paint dim = new Paint();
        dim.setColor(Color.parseColor("#dd000000"));
        canvas.drawRect(0, 0, screenWidth, screenHeight, dim);

        float cx = screenWidth / 2f;
        float cy = screenHeight / 2f;

        // Panel
        Paint panel = new Paint();
        panel.setColor(Color.parseColor("#1a1a2e"));
        panel.setAntiAlias(true);
        canvas.drawRoundRect(
                new RectF(cx - 260f, cy - 160f, cx + 260f, cy + 160f),
                24, 24, panel);

        Paint panelBorder = new Paint();
        panelBorder.setColor(Color.parseColor("#e94560"));
        panelBorder.setStyle(Paint.Style.STROKE);
        panelBorder.setStrokeWidth(4f);
        panelBorder.setAntiAlias(true);
        canvas.drawRoundRect(
                new RectF(cx - 260f, cy - 160f, cx + 260f, cy + 160f),
                24, 24, panelBorder);

        // Title
        Paint title = new Paint();
        title.setColor(Color.parseColor("#e94560"));
        title.setTextSize(72f);
        title.setTextAlign(Paint.Align.CENTER);
        title.setFakeBoldText(true);
        title.setAntiAlias(true);
        canvas.drawText("GAME OVER", cx, cy - 70f, title);

        // Divider
        Paint divider = new Paint();
        divider.setColor(Color.parseColor("#e94560"));
        divider.setStrokeWidth(2f);
        canvas.drawLine(cx - 180f, cy - 40f, cx + 180f, cy - 40f, divider);

        // Score
        Paint scorePaint = new Paint();
        scorePaint.setColor(Color.WHITE);
        scorePaint.setTextSize(44f);
        scorePaint.setTextAlign(Paint.Align.CENTER);
        scorePaint.setFakeBoldText(true);
        scorePaint.setAntiAlias(true);
        canvas.drawText("Score: " + score, cx, cy + 20f, scorePaint);

        // Sub-label
        Paint sub = new Paint();
        sub.setColor(Color.parseColor("#aaaaaa"));
        sub.setTextSize(26f);
        sub.setTextAlign(Paint.Align.CENTER);
        sub.setAntiAlias(true);
        canvas.drawText("Enemies defeated: " + score, cx, cy + 70f, sub);

        // Hint
        Paint hint = new Paint();
        hint.setColor(Color.parseColor("#666666"));
        hint.setTextSize(20f);
        hint.setTextAlign(Paint.Align.CENTER);
        hint.setAntiAlias(true);
        canvas.drawText("Press PAUSE then back to restart", cx, cy + 130f, hint);
    }

    // ══════════════════════════════════════════════════════════════
    //  PAUSE / RESUME
    // ══════════════════════════════════════════════════════════════
    public void pause() {
        if (gameThread != null) {
            gameThread.setRunning(false);
            try { gameThread.join(500); }
            catch (InterruptedException e) { e.printStackTrace(); }
        }
    }

    public void resume() {
        if (gameOver) return;
        if (screenWidth > 0) {
            lastFrameTime = System.currentTimeMillis();
            gameThread    = new GameThread(getHolder(), this);
            gameThread.setRunning(true);
            gameThread.start();
        }
    }

    // ══════════════════════════════════════════════════════════════
    //  GAME LOOP THREAD
    // ══════════════════════════════════════════════════════════════
    static class GameThread extends Thread {

        private final SurfaceHolder surfaceHolder;
        private final GameView      gameView;
        private volatile boolean    isRunning;

        private static final int  TARGET_FPS = 60;
        private static final long FRAME_TIME = 1000L / TARGET_FPS;

        GameThread(SurfaceHolder holder, GameView view) {
            this.surfaceHolder = holder;
            this.gameView      = view;
        }

        void setRunning(boolean running) { this.isRunning = running; }

        @Override
        public void run() {
            while (isRunning) {
                long start  = System.currentTimeMillis();
                Canvas canvas = null;
                try {
                    canvas = surfaceHolder.lockCanvas();
                    if (canvas != null) {
                        synchronized (surfaceHolder) {
                            gameView.update();
                            gameView.draw(canvas);
                        }
                    }
                } finally {
                    if (canvas != null) surfaceHolder.unlockCanvasAndPost(canvas);
                }
                long elapsed = System.currentTimeMillis() - start;
                long sleep   = FRAME_TIME - elapsed;
                if (sleep > 0) {
                    try { Thread.sleep(sleep); }
                    catch (InterruptedException ignored) {}
                }
            }
        }
    }
}