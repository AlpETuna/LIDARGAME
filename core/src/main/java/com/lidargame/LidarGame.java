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
    private static final float PLAYER_HEIGHT = 1.6f;
    private static final float PLAYER_RADIUS = 1.2f;
    private static final float MOVE_SPEED = 16f;
    private static final float MOUSE_SENSITIVITY = 0.25f;
    private static final float MAX_RAY_DISTANCE = 65f;
    private static final float FIRE_RATE = 30f; // shots per second
    private static final float DOT_LIFETIME = 2.2f;

    private PerspectiveCamera camera;
    private float yaw = 0f;
    private float pitch = 0f;

    private final Vector3 position = new Vector3(10f, PLAYER_HEIGHT, 10f);
    private boolean cursorCaptured = true;

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

        // Dots rendered as small spheres; scaled per-hit during shooting.
        Material dotMaterial = new Material();
        dotMaterial.set(ColorAttribute.createDiffuse(Color.WHITE));
        dotMaterial.set(ColorAttribute.createEmissive(Color.WHITE));
        dotModel = modelBuilder.createSphere(
                0.3f, 0.3f, 0.3f, 8, 8,
                dotMaterial,
                com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);

        // Generic box for obstacles.
        boxModel = modelBuilder.createBox(1f, 1f, 1f,
                new Material(ColorAttribute.createDiffuse(new Color(0.25f, 0.28f, 0.32f, 1f))),
                com.badlogic.gdx.graphics.VertexAttributes.Usage.Position | com.badlogic.gdx.graphics.VertexAttributes.Usage.Normal);
    }

    private void buildWorld() {
        floorInstance = new ModelInstance(floorModel);

        // Sparse "backrooms" corridors using long blocks
        for (int i = -2; i <= 2; i++) {
            addBox(new Vector3(i * 18f, 6f, 0f), new Vector3(4f, 12f, 80f));
        }
        for (int i = -2; i <= 2; i++) {
            addBox(new Vector3(0f, 6f, i * 18f), new Vector3(80f, 12f, 4f));
        }
        addBox(new Vector3(-25f, 4f, -25f), new Vector3(14f, 8f, 14f));
        addBox(new Vector3(22f, 4f, 22f), new Vector3(14f, 8f, 14f));
        addBox(new Vector3(-30f, 8f, 18f), new Vector3(6f, 16f, 10f));
        addBox(new Vector3(30f, 8f, -18f), new Vector3(6f, 16f, 10f));

        // Perimeter walls to keep the player inside.
        addBox(new Vector3(0f, 3f, -WORLD_SIZE), new Vector3(WORLD_SIZE * 2f, 6f, 2f), true);
        addBox(new Vector3(0f, 3f, WORLD_SIZE), new Vector3(WORLD_SIZE * 2f, 6f, 2f), true);
        addBox(new Vector3(-WORLD_SIZE, 3f, 0f), new Vector3(2f, 6f, WORLD_SIZE * 2f), true);
        addBox(new Vector3(WORLD_SIZE, 3f, 0f), new Vector3(2f, 6f, WORLD_SIZE * 2f), true);
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
        modelBatch.render(floorInstance, environment);
        for (BoxObstacle ob : obstacles) {
            modelBatch.render(ob.instance, environment);
        }
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
        if (cursorCaptured) {
            float deltaX = -Gdx.input.getDeltaX() * MOUSE_SENSITIVITY;
            float deltaY = -Gdx.input.getDeltaY() * MOUSE_SENSITIVITY;
            yaw = (yaw + deltaX) % 360f;
            pitch = MathUtils.clamp(pitch + deltaY, -85f, 85f);
        }

        Vector3 move = new Vector3();
        Vector3 forward = new Vector3(camera.direction.x, 0f, camera.direction.z).nor();
        Vector3 right = new Vector3(forward).crs(Vector3.Y).nor();

        if (Gdx.input.isKeyPressed(Input.Keys.W)) move.add(forward);
        if (Gdx.input.isKeyPressed(Input.Keys.S)) move.sub(forward);
        if (Gdx.input.isKeyPressed(Input.Keys.A)) move.sub(right);
        if (Gdx.input.isKeyPressed(Input.Keys.D)) move.add(right);

        if (!move.isZero()) {
            move.nor().scl(MOVE_SPEED * delta);
            Vector3 candidate = new Vector3(position).add(move);
            candidate.y = PLAYER_HEIGHT; // lock height
            candidate.x = MathUtils.clamp(candidate.x, -WORLD_SIZE + PLAYER_RADIUS, WORLD_SIZE - PLAYER_RADIUS);
            candidate.z = MathUtils.clamp(candidate.z, -WORLD_SIZE + PLAYER_RADIUS, WORLD_SIZE - PLAYER_RADIUS);
            if (!collides(candidate)) {
                position.set(candidate);
            }
        }

        boolean shootPressed = Gdx.input.isButtonPressed(Input.Buttons.LEFT) || Gdx.input.isKeyPressed(Input.Keys.SPACE);
        autoFireTimer -= delta;
        if (shootPressed && autoFireTimer <= 0f) {
            shootLidar();
            autoFireTimer = 1f / FIRE_RATE;
        }

        updateDots(delta);
    }

    private boolean collides(Vector3 candidate) {
        // Temporarily disabled for testing
        return false;
    }

    private float autoFireTimer = 0f;

    private void shootLidar() {
        Ray ray = new Ray();
        Vector3 dir = new Vector3();
        Vector3 hitPoint = new Vector3();

        // Add random spray to the direction
        float spreadAngle = 8f;
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
        camera.direction.set(0f, 0f, -1f)
                .rotate(Vector3.Y, yaw)
                .rotate(Vector3.X, pitch)
                .nor();
        camera.up.set(Vector3.Y);
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
