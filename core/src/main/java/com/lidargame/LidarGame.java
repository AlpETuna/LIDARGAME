package com.lidargame;

import com.badlogic.gdx.ApplicationAdapter;
import com.badlogic.gdx.Gdx;
import com.badlogic.gdx.Input;
import com.badlogic.gdx.graphics.Color;
import com.badlogic.gdx.graphics.GL20;
import com.badlogic.gdx.graphics.PerspectiveCamera;
import com.badlogic.gdx.graphics.g2d.BitmapFont;
import com.badlogic.gdx.graphics.g2d.SpriteBatch;
import com.badlogic.gdx.graphics.g3d.Environment;
import com.badlogic.gdx.graphics.g3d.Material;
import com.badlogic.gdx.graphics.g3d.Model;
import com.badlogic.gdx.graphics.g3d.ModelBatch;
import com.badlogic.gdx.graphics.g3d.ModelInstance;
import com.badlogic.gdx.graphics.g3d.attributes.ColorAttribute;
import com.badlogic.gdx.graphics.g3d.environment.DirectionalLight;
import com.badlogic.gdx.graphics.g3d.utils.ModelBuilder;
import com.badlogic.gdx.math.MathUtils;
import com.badlogic.gdx.math.Vector3;
import com.badlogic.gdx.math.collision.Ray;
import com.badlogic.gdx.math.collision.BoundingBox;
import com.badlogic.gdx.utils.Array;

/**
 * First-person lidar sandbox: move around and "fire" the scanner to reveal hit dots.
 * Dot size grows when the hit point is closer; dots only appear when you shoot.
 */
public class LidarGame extends ApplicationAdapter {

    private static final float WORLD_SIZE = 90f;
    private static final float PLAYER_HEIGHT = 1.3f;
    private static final float PLAYER_RADIUS = 1.3f;
    private static final float MOVE_SPEED = 16f;
    private static final float GRAVITY = -30f;
    private static final float JUMP_VELOCITY = 12f;
    private static final float MOUSE_SENSITIVITY = 0.25f;
    private static final float MAX_RAY_DISTANCE = 30f;
    private static final float FIRE_RATE = 100f; // shots per second
    private static final float DOT_LIFETIME = 5f;

    private PerspectiveCamera camera;
    private float yaw = 0f;
    private float pitch = 0f;

    private final Vector3 position = new Vector3(10f, PLAYER_HEIGHT, 10f);
    private boolean cursorCaptured = true;
    private boolean testingMode = false;
    private float velocityY = 0f;
    private boolean onGround = false;

    private ModelBatch modelBatch;
    private ModelBuilder modelBuilder;
    private Environment environment;

    private Model floorModel;
    private Model dotModel;
    private Model boxModel;
    private ModelInstance floorInstance;
    private final Array<BoxObstacle> obstacles = new Array<>();
    private final Array<Dot> lidarDots = new Array<>();

    private SpriteBatch spriteBatch;
    private BitmapFont font;
    private com.badlogic.gdx.graphics.OrthographicCamera uiCamera;

    @Override
    public void create() {
        setupCamera();
        modelBatch = new ModelBatch();
        modelBuilder = new ModelBuilder();
        environment = buildEnvironment();
        buildModels();
        buildWorld();

        spriteBatch = new SpriteBatch();
        font = new BitmapFont();
        font.setColor(Color.WHITE);
        uiCamera = new com.badlogic.gdx.graphics.OrthographicCamera(Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        uiCamera.position.set(uiCamera.viewportWidth / 2f, uiCamera.viewportHeight / 2f, 0f);
        uiCamera.update();

        Gdx.input.setCursorCatched(true);
    }

    private void setupCamera() {
        camera = new PerspectiveCamera(70f, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        camera.near = 0.1f;
        camera.far = 200f;
        updateCameraDirection();
        camera.position.set(position);
        camera.update();
    }

    private Environment buildEnvironment() {
        Environment env = new Environment();
        env.set(new ColorAttribute(ColorAttribute.AmbientLight, 0f, 0f, 0f, 1f));
        env.add(new DirectionalLight().set(0.8f, 0.8f, 0.8f, -0.5f, -1f, -0.5f));
        return env;
    }

    private void buildModels() {
        // Ground plane
        floorModel = modelBuilder.createRect(
                -WORLD_SIZE, 0f, -WORLD_SIZE,
                WORLD_SIZE, 0f, -WORLD_SIZE,
                WORLD_SIZE, 0f, WORLD_SIZE,
                -WORLD_SIZE, 0f, WORLD_SIZE,
                0f, 1f, 0f,
                new Material(ColorAttribute.createDiffuse(new Color(0.08f, 0.1f, 0.12f, 1f))),
                com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);

        // Dots rendered as small spheres; color set per-hit based on distance
        dotModel = modelBuilder.createSphere(
                0.3f, 0.3f, 0.3f, 8, 8,
                new Material(),
                com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);

        // Generic box for obstacles.
        boxModel = modelBuilder.createBox(1f, 1f, 1f,
                new Material(ColorAttribute.createDiffuse(new Color(0.25f, 0.28f, 0.32f, 1f))),
                com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);
    }

    private void buildWorld() {
        floorInstance = new ModelInstance(floorModel);
        
        // Generate 3D cave using multi-octave Perlin noise for organic shapes
        int gridSize = 2;
        float scale1 = 0.1f;
        float scale2 = 0.25f;
        float threshold = 0.3f;
        
        for (int x = -20; x <= 20; x += gridSize) {
            for (int y = 0; y <= 30; y += gridSize) {
                for (int z = -20; z <= 20; z += gridSize) {
                    // Combine multiple noise octaves for more organic terrain
                    float noise1 = perlin3D(x * scale1, y * scale1, z * scale1);
                    float noise2 = perlin3D(x * scale2, y * scale2, z * scale2) * 0.5f;
                    float combinedNoise = noise1 + noise2;
                    
                    if (combinedNoise > threshold) {
                        // Vary block size slightly for more organic look
                        float sizeVariation = 1f + MathUtils.random(-0.3f, 0.3f);
                        addBox(new Vector3(x, y, z), new Vector3(gridSize * sizeVariation, gridSize * sizeVariation, gridSize * sizeVariation));
                    }
                }
            }
        }
        
        // Add boundary walls to contain the player
        int wallThickness = 2;
        for (int x = -24; x <= 24; x += gridSize) {
            for (int y = 0; y <= 30; y += gridSize) {
                // Front and back walls
                addBox(new Vector3(x, y, -24), new Vector3(gridSize, gridSize, wallThickness));
                addBox(new Vector3(x, y, 24), new Vector3(gridSize, gridSize, wallThickness));
            }
        }
        for (int z = -24; z <= 24; z += gridSize) {
            for (int y = 0; y <= 30; y += gridSize) {
                // Left and right walls
                addBox(new Vector3(-24, y, z), new Vector3(wallThickness, gridSize, gridSize));
                addBox(new Vector3(24, y, z), new Vector3(wallThickness, gridSize, gridSize));
            }
        }
        // Ceiling
        for (int x = -24; x <= 24; x += gridSize) {
            for (int z = -24; z <= 24; z += gridSize) {
                addBox(new Vector3(x, 34, z), new Vector3(gridSize, wallThickness, gridSize));
            }
        }
        
        // Find safe spawn position
        position.set(0f, 15f, 0f);
        boolean foundSafe = false;
        for (int attempts = 0; attempts < 200; attempts++) {
            float testX = MathUtils.random(-15f, 15f);
            float testZ = MathUtils.random(-15f, 15f);
            float testY = 15f;
            Vector3 testPos = new Vector3(testX, testY, testZ);
            
            boolean safe = true;
            for (BoxObstacle ob : obstacles) {
                if (ob.bounds.contains(testPos)) {
                    safe = false;
                    break;
                }
            }
            
            if (safe) {
                position.set(testPos);
                foundSafe = true;
                break;
            }
        }
        
        if (!foundSafe) {
            position.set(0f, 25f, 0f);
        }
    }
    
    private float perlin3D(float x, float y, float z) {
        int X = (int)Math.floor(x) & 255;
        int Y = (int)Math.floor(y) & 255;
        int Z = (int)Math.floor(z) & 255;
        
        x -= Math.floor(x);
        y -= Math.floor(y);
        z -= Math.floor(z);
        
        float u = fade(x);
        float v = fade(y);
        float w = fade(z);
        
        int A = p[X] + Y;
        int AA = p[A] + Z;
        int AB = p[A + 1] + Z;
        int B = p[X + 1] + Y;
        int BA = p[B] + Z;
        int BB = p[B + 1] + Z;
        
        return lerp(w, lerp(v, lerp(u, grad(p[AA], x, y, z),
                                       grad(p[BA], x - 1, y, z)),
                               lerp(u, grad(p[AB], x, y - 1, z),
                                       grad(p[BB], x - 1, y - 1, z))),
                       lerp(v, lerp(u, grad(p[AA + 1], x, y, z - 1),
                                       grad(p[BA + 1], x - 1, y, z - 1)),
                               lerp(u, grad(p[AB + 1], x, y - 1, z - 1),
                                       grad(p[BB + 1], x - 1, y - 1, z - 1))));
    }
    
    private float fade(float t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }
    
    private float lerp(float t, float a, float b) {
        return a + t * (b - a);
    }
    
    private float grad(int hash, float x, float y, float z) {
        int h = hash & 15;
        float u = h < 8 ? x : y;
        float v = h < 4 ? y : h == 12 || h == 14 ? x : z;
        return ((h & 1) == 0 ? u : -u) + ((h & 2) == 0 ? v : -v);
    }
    
    private static final int[] p = new int[512];
    static {
        int[] permutation = {151,160,137,91,90,15,131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,
            8,99,37,240,21,10,23,190,6,148,247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,57,177,33,
            88,237,149,56,87,174,20,125,136,171,168,68,175,74,165,71,134,139,48,27,166,77,146,158,231,83,111,
            229,122,60,211,133,230,220,105,92,41,55,46,245,40,244,102,143,54,65,25,63,161,1,216,80,73,209,76,
            132,187,208,89,18,169,200,196,135,130,116,188,159,86,164,100,109,198,173,186,3,64,52,217,226,250,
            124,123,5,202,38,147,118,126,255,82,85,212,207,206,59,227,47,16,58,17,182,189,28,42,223,183,170,
            213,119,248,152,2,44,154,163,70,221,153,101,155,167,43,172,9,129,22,39,253,19,98,108,110,79,113,
            224,232,178,185,112,104,218,246,97,228,251,34,242,193,238,210,144,12,191,179,162,241,81,51,145,
            235,249,14,239,107,49,192,214,31,181,199,106,157,184,84,204,176,115,121,50,45,127,4,150,254,138,
            236,205,93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180};
        for (int i = 0; i < 256; i++) p[256 + i] = p[i] = permutation[i];
    }

    private void addBox(Vector3 center, Vector3 size) {
        addBox(center, size, false);
    }

    private void addBox(Vector3 center, Vector3 size, boolean isWall) {
        ModelInstance instance = new ModelInstance(boxModel);
        instance.transform.setToTranslation(center);
        instance.transform.scale(size.x, size.y, size.z);
        BoxObstacle ob = new BoxObstacle(center, size, instance, isWall);
        obstacles.add(ob);
    }

    @Override
    public void render() {
        float delta = Gdx.graphics.getDeltaTime();
        handleInput(delta);
        camera.position.set(position);
        updateCameraDirection();
        camera.update();

        Gdx.gl.glViewport(0, 0, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        Gdx.gl.glClearColor(0f, 0f, 0f, 1f);
        Gdx.gl.glClear(GL20.GL_COLOR_BUFFER_BIT | GL20.GL_DEPTH_BUFFER_BIT);
        Gdx.gl.glEnable(GL20.GL_DEPTH_TEST);

        modelBatch.begin(camera);
        if (testingMode) {
            // In testing mode, show everything
            modelBatch.render(floorInstance, environment);
            for (BoxObstacle ob : obstacles) {
                modelBatch.render(ob.instance, environment);
            }
        }
        // Always render dots
        for (Dot hit : lidarDots) {
            modelBatch.render(hit.instance, environment);
        }
        modelBatch.end();
        Gdx.gl.glDisable(GL20.GL_DEPTH_TEST);

        renderUi();
    }

    private void handleInput(float delta) {
        if (Gdx.input.isKeyJustPressed(Input.Keys.ESCAPE)) {
            cursorCaptured = !cursorCaptured;
            Gdx.input.setCursorCatched(cursorCaptured);
        }
        if (Gdx.input.isKeyJustPressed(Input.Keys.T)) {
            testingMode = !testingMode;
            // Toggle environment lighting
            if (testingMode) {
                environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0.6f, 0.6f, 0.6f, 1f));
            } else {
                environment.set(new ColorAttribute(ColorAttribute.AmbientLight, 0f, 0f, 0f, 1f));
            }
        }
        if (cursorCaptured) {
            float deltaX = -Gdx.input.getDeltaX() * MOUSE_SENSITIVITY;
            float deltaY = -Gdx.input.getDeltaY() * MOUSE_SENSITIVITY;
            yaw = (yaw + deltaX) % 360f;
            pitch = MathUtils.clamp(pitch + deltaY, -85f, 85f);
        }

        Vector3 move = new Vector3();
        Vector3 forward = new Vector3(camera.direction.x, 0f, camera.direction.z).nor();
        Vector3 right = new Vector3(forward).crs(Vector3.Y).nor();

        // Horizontal movement
        if (Gdx.input.isKeyPressed(Input.Keys.W)) move.add(forward);
        if (Gdx.input.isKeyPressed(Input.Keys.S)) move.sub(forward);
        if (Gdx.input.isKeyPressed(Input.Keys.A)) move.sub(right);
        if (Gdx.input.isKeyPressed(Input.Keys.D)) move.add(right);

        if (!move.isZero()) {
            move.nor().scl(MOVE_SPEED * delta);
            Vector3 candidate = new Vector3(position).add(move);
            candidate.x = MathUtils.clamp(candidate.x, -WORLD_SIZE + PLAYER_RADIUS, WORLD_SIZE - PLAYER_RADIUS);
            candidate.z = MathUtils.clamp(candidate.z, -WORLD_SIZE + PLAYER_RADIUS, WORLD_SIZE - PLAYER_RADIUS);
            if (!collides(candidate)) {
                position.x = candidate.x;
                position.z = candidate.z;
            }
        }
        
        // Jump
        if (Gdx.input.isKeyJustPressed(Input.Keys.SPACE) && onGround) {
            velocityY = JUMP_VELOCITY;
            onGround = false;
        }
        
        // Apply gravity
        velocityY += GRAVITY * delta;
        float newY = position.y + velocityY * delta;
        
        // Check vertical collision with blocks
        Vector3 testPos = new Vector3(position.x, newY, position.z);
        boolean hitBlock = false;
        
        for (BoxObstacle ob : obstacles) {
            if (ob.collidesVertical(testPos, PLAYER_RADIUS, velocityY < 0)) {
                if (velocityY < 0) {
                    // Landing on top of block
                    position.y = ob.bounds.max.y + PLAYER_HEIGHT;
                    velocityY = 0f;
                    onGround = true;
                    hitBlock = true;
                    break;
                } else {
                    // Hitting ceiling
                    position.y = ob.bounds.min.y - 0.1f;
                    velocityY = 0f;
                    hitBlock = true;
                    break;
                }
            }
        }
        
        if (!hitBlock) {
            position.y = newY;
            // Ground collision
            if (position.y <= PLAYER_HEIGHT) {
                position.y = PLAYER_HEIGHT;
                velocityY = 0f;
                onGround = true;
            } else {
                onGround = false;
            }
        }

        boolean shootPressed = Gdx.input.isButtonPressed(Input.Buttons.LEFT);
        autoFireTimer -= delta;
        if (shootPressed && autoFireTimer <= 0f) {
            shootLidar();
            autoFireTimer = 1f / FIRE_RATE;
        }

        updateDots(delta);
    }

    private boolean collides(Vector3 candidate) {
        for (BoxObstacle ob : obstacles) {
            if (ob.collides3D(candidate, PLAYER_RADIUS, PLAYER_HEIGHT)) return true;
        }
        return false;
    }

    private float autoFireTimer = 0f;

    private void shootLidar() {
        Ray ray = new Ray();
        Vector3 dir = new Vector3();
        Vector3 hitPoint = new Vector3();

        // Add random spray to the direction
        float spreadAngle = 15f;
        float randomYaw = MathUtils.random(-spreadAngle, spreadAngle);
        float randomPitch = MathUtils.random(-spreadAngle, spreadAngle);
        
        dir.set(camera.direction).nor();
        dir.rotate(Vector3.Y, randomYaw);
        dir.rotate(new Vector3(camera.direction).crs(Vector3.Y).nor(), randomPitch);
        dir.nor();
        
        ray.set(position, dir);

        float closest = MAX_RAY_DISTANCE;
        boolean hitFound = false;

        // Check floor intersection
        if (ray.direction.y != 0) {
            float t = (0f - ray.origin.y) / ray.direction.y;
            if (t > 0 && t < closest) {
                Vector3 floorHit = new Vector3(ray.origin).mulAdd(ray.direction, t);
                if (Math.abs(floorHit.x) <= WORLD_SIZE && Math.abs(floorHit.z) <= WORLD_SIZE) {
                    closest = t;
                    hitPoint.set(floorHit);
                    hitFound = true;
                }
            }
        }

        // Check obstacles
        for (BoxObstacle ob : obstacles) {
            float dist = intersectRayAabb(ray, ob.bounds, MAX_RAY_DISTANCE);
            if (dist >= 0f && dist < closest) {
                closest = dist;
                hitPoint.set(ray.origin).mulAdd(ray.direction, dist);
                hitFound = true;
            }
        }

        if (hitFound) {
            float size = MathUtils.lerp(0.6f, 0.2f, closest / MAX_RAY_DISTANCE);
            ModelInstance dot = new ModelInstance(dotModel);
            dot.transform.setToTranslation(hitPoint);
            dot.transform.scale(size, size, size);
            
            // Thermal gradient: red (close) -> yellow (medium) -> blue (far)
            float t = closest / MAX_RAY_DISTANCE;
            Color thermalColor = new Color();
            if (t < 0.5f) {
                // Red to Yellow (0.0 to 0.5)
                float blend = t * 2f;
                thermalColor.set(1f, blend, 0f, 1f);
            } else {
                // Yellow to Blue (0.5 to 1.0)
                float blend = (t - 0.5f) * 2f;
                thermalColor.set(1f - blend, 1f - blend, blend, 1f);
            }
            
            Material mat = dot.materials.get(0);
            mat.set(ColorAttribute.createDiffuse(thermalColor));
            mat.set(ColorAttribute.createEmissive(thermalColor));
            
            lidarDots.add(new Dot(dot, DOT_LIFETIME));
        }
    }

    private void updateDots(float delta) {
        for (int i = lidarDots.size - 1; i >= 0; i--) {
            Dot dot = lidarDots.get(i);
            dot.timeLeft -= delta;
            if (dot.timeLeft <= 0f) {
                lidarDots.removeIndex(i);
            }
        }
    }

    private float intersectRayAabb(Ray ray, BoundingBox box, float maxDistance) {
        float tMin = 0f;
        float tMax = maxDistance;

        IntervalResult resX = updateInterval(ray.origin.x, ray.direction.x, box.min.x, box.max.x, tMin, tMax);
        if (!resX.valid) return -1f;
        tMin = resX.tMin;
        tMax = resX.tMax;

        IntervalResult resY = updateInterval(ray.origin.y, ray.direction.y, box.min.y, box.max.y, tMin, tMax);
        if (!resY.valid) return -1f;
        tMin = resY.tMin;
        tMax = resY.tMax;

        IntervalResult resZ = updateInterval(ray.origin.z, ray.direction.z, box.min.z, box.max.z, tMin, tMax);
        if (!resZ.valid) return -1f;
        tMin = resZ.tMin;
        tMax = resZ.tMax;

        if (tMax < 0f) return -1f;
        float hit = tMin >= 0f ? tMin : tMax;
        return hit <= maxDistance ? hit : -1f;
    }

    private IntervalResult updateInterval(float origin, float direction, float min, float max, float tMin, float tMax) {
        if (MathUtils.isZero(direction)) {
            if (origin < min || origin > max) return IntervalResult.invalid();
            return new IntervalResult(tMin, tMax, true);
        }
        float invD = 1f / direction;
        float t1 = (min - origin) * invD;
        float t2 = (max - origin) * invD;
        if (t1 > t2) {
            float tmp = t1;
            t1 = t2;
            t2 = tmp;
        }
        float newMin = Math.max(t1, tMin);
        float newMax = Math.min(t2, tMax);
        if (newMax < newMin) return IntervalResult.invalid();
        return new IntervalResult(newMin, newMax, true);
    }

    private void renderUi() {
        uiCamera.setToOrtho(false, Gdx.graphics.getWidth(), Gdx.graphics.getHeight());
        uiCamera.update();
        spriteBatch.setProjectionMatrix(uiCamera.combined);
        spriteBatch.begin();
        float y = uiCamera.viewportHeight - 20f;
        spriteBatch.setColor(Color.WHITE);
        font.draw(spriteBatch, "Backrooms: WASD move | Mouse look | Hold Left-click/Space to spray lidar", 20f, y);
        font.draw(spriteBatch, "Each shot lights a dot for a couple seconds; closer hits are larger.", 20f, y - 20f);
        font.draw(spriteBatch, cursorCaptured ? "Press ESC to free the mouse." : "Press ESC to recapture the mouse.", 20f, y - 40f);
        
        // Top right debug info
        boolean isFiring = Gdx.input.isButtonPressed(Input.Buttons.LEFT) || Gdx.input.isKeyPressed(Input.Keys.SPACE);
        boolean wPressed = Gdx.input.isKeyPressed(Input.Keys.W);
        boolean sPressed = Gdx.input.isKeyPressed(Input.Keys.S);
        boolean aPressed = Gdx.input.isKeyPressed(Input.Keys.A);
        boolean dPressed = Gdx.input.isKeyPressed(Input.Keys.D);
        String posText = String.format("Pos: %.1f, %.1f, %.1f", position.x, position.y, position.z);
        String lookText = String.format("Look: Yaw %.0f Pitch %.0f", yaw, pitch);
        String fireText = "Firing: " + (isFiring ? "YES" : "NO");
        String dotsText = "Dots: " + lidarDots.size;
        String keysText = String.format("Keys: W=%s S=%s A=%s D=%s", wPressed, sPressed, aPressed, dPressed);
        
        float rightX = uiCamera.viewportWidth - 20f;
        font.draw(spriteBatch, posText, rightX - 250f, y);
        font.draw(spriteBatch, lookText, rightX - 250f, y - 20f);
        font.draw(spriteBatch, fireText, rightX - 250f, y - 40f);
        font.draw(spriteBatch, dotsText, rightX - 250f, y - 60f);
        font.draw(spriteBatch, keysText, rightX - 250f, y - 80f);
        
        spriteBatch.end();
    }

    private void updateCameraDirection() {
        float pitchRad = pitch * MathUtils.degreesToRadians;
        float yawRad = yaw * MathUtils.degreesToRadians;
        
        camera.direction.set(
            MathUtils.cos(pitchRad) * MathUtils.sin(yawRad),
            MathUtils.sin(pitchRad),
            MathUtils.cos(pitchRad) * MathUtils.cos(yawRad)
        ).nor();
        
        camera.up.set(0f, 1f, 0f);
    }

    @Override
    public void resize(int width, int height) {
        camera.viewportWidth = width;
        camera.viewportHeight = height;
        camera.update();
    }

    @Override
    public void dispose() {
        modelBatch.dispose();
        floorModel.dispose();
        dotModel.dispose();
        boxModel.dispose();
        spriteBatch.dispose();
        font.dispose();
    }

    private static class BoxObstacle {
        final Vector3 center;
        final Vector3 size;
        final ModelInstance instance;
        final BoundingBox bounds;
        final boolean isWall;

        BoxObstacle(Vector3 center, Vector3 size, ModelInstance instance) {
            this(center, size, instance, false);
        }

        BoxObstacle(Vector3 center, Vector3 size, ModelInstance instance, boolean isWall) {
            this.center = new Vector3(center);
            this.size = new Vector3(size);
            this.instance = instance;
            this.isWall = isWall;
            Vector3 half = new Vector3(size).scl(0.5f);
            this.bounds = new BoundingBox(new Vector3(center).sub(half), new Vector3(center).add(half));
        }

        boolean collides(Vector3 point, float radius) {
            float dx = Math.abs(point.x - center.x);
            float dz = Math.abs(point.z - center.z);
            return dx <= (size.x * 0.5f + radius) && dz <= (size.z * 0.5f + radius);
        }
        
        boolean collides3D(Vector3 point, float radius, float height) {
            // Check horizontal collision with expanded radius
            float dx = Math.abs(point.x - center.x);
            float dz = Math.abs(point.z - center.z);
            
            if (dx > (size.x * 0.5f + radius) || dz > (size.z * 0.5f + radius)) {
                return false;
            }
            
            // Check vertical overlap - player cylinder from (y - height) to y
            float playerBottom = point.y - height;
            float playerTop = point.y + 0.5f;
            float boxBottom = bounds.min.y;
            float boxTop = bounds.max.y;
            
            return !(playerTop < boxBottom || playerBottom > boxTop);
        }
        
        boolean collidesVertical(Vector3 point, float radius, boolean checkBelow) {
            float dx = Math.abs(point.x - center.x);
            float dz = Math.abs(point.z - center.z);
            
            if (dx > (size.x * 0.5f + radius) || dz > (size.z * 0.5f + radius)) {
                return false;
            }
            
            if (checkBelow) {
                // Check if landing on top
                return point.y >= bounds.max.y - 0.5f && point.y <= bounds.max.y + 2f;
            } else {
                // Check if hitting ceiling
                return point.y <= bounds.min.y + 0.5f && point.y >= bounds.min.y - 2f;
            }
        }
    }

    private static class Dot {
        final ModelInstance instance;
        float timeLeft;

        Dot(ModelInstance instance, float timeLeft) {
            this.instance = instance;
            this.timeLeft = timeLeft;
        }
    }

    private static class IntervalResult {
        final float tMin;
        final float tMax;
        final boolean valid;

        IntervalResult(float tMin, float tMax, boolean valid) {
            this.tMin = tMin;
            this.tMax = tMax;
            this.valid = valid;
        }

        static IntervalResult invalid() {
            return new IntervalResult(0f, 0f, false);
        }
    }
}
