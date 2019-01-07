package org.gephi.viz.engine;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.Rect2D;
import org.gephi.viz.engine.pipeline.RenderingLayer;
import org.gephi.viz.engine.spi.InputListener;
import org.gephi.viz.engine.spi.PipelinedExecutor;
import org.gephi.viz.engine.spi.Renderer;
import org.gephi.viz.engine.spi.RenderingTarget;
import org.gephi.viz.engine.spi.WorldUpdater;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector2f;
import org.joml.Vector2fc;
import org.joml.Vector3f;
import org.openide.util.Lookup;
import org.openide.util.lookup.AbstractLookup;
import org.openide.util.lookup.InstanceContent;

/**
 *
 * @author Eduardo Ramos
 * @param <R> Rendering target
 * @param <I> Events type
 */
public class VizEngine<R extends RenderingTarget, I> {

    public static final int DEFAULT_MAX_WORLD_UPDATES_PER_SECOND = 30;

    //Rendering target
    private final R renderingTarget;
    private boolean isSetUp = false;

    //State
    private int width = 0;
    private int height = 0;
    private Rect2D viewBoundaries = new Rect2D(0, 0, 0, 0);

    //Matrix
    private final Matrix4f modelMatrix = new Matrix4f().identity();
    private final Matrix4f viewMatrix = new Matrix4f();
    private final Matrix4f projectionMatrix = new Matrix4f();
    private final Matrix4f modelViewProjectionMatrix = new Matrix4f();
    private final Matrix4f modelViewProjectionMatrixInverted = new Matrix4f();

    private final float[] modelViewProjectionMatrixFloats = new float[16];

    private float zoom = 0.3f;
    private final Vector2f translate = new Vector2f();

    //Renderers:
    private final Set<Renderer<R>> allRenderers = new LinkedHashSet<>();
    private final List<Renderer<R>> renderersPipeline = new ArrayList<>();

    //World updaters:
    private final Set<WorldUpdater<R>> allUpdaters = new LinkedHashSet<>();
    private final List<WorldUpdater<R>> updatersPipeline = new ArrayList<>();
    private ExecutorService updatersThreadPool;

    //Input listeners:
    private final List<I> eventsQueue = Collections.synchronizedList(new ArrayList<>());
    private final Set<InputListener<R, I>> allInuptListeners = new LinkedHashSet<>();
    private final List<InputListener<R, I>> inputListenersPipeline = new ArrayList<>();

    //Graph:
    private final GraphModel graphModel;

    //Settings:
    private final float[] backgroundColor = new float[]{1, 1, 1, 1};
    private int maxWorldUpdatesPerSecond = DEFAULT_MAX_WORLD_UPDATES_PER_SECOND;

    //Lookup for communication between components:
    private final InstanceContent instanceContent;
    private final AbstractLookup lookup;

    public VizEngine(GraphModel graphModel, R renderingTarget) {
        this.graphModel = Objects.requireNonNull(graphModel, "graphModel mandatory");
        this.instanceContent = new InstanceContent();
        this.lookup = new AbstractLookup(instanceContent);
        this.renderingTarget = Objects.requireNonNull(renderingTarget, "renderingTarget mandatory");
        loadModelViewProjection();
    }

    public void setup() {
        if (isSetUp) {
            throw new IllegalStateException("Setup already called");
        }

        final int numThreads = Math.max(Math.min(updatersPipeline.size(), 4), 1);
        updatersThreadPool = Executors.newFixedThreadPool(numThreads, new ThreadFactory() {
            private int id = 1;

            @Override
            public Thread newThread(Runnable r) {
                return new Thread(r, "World Updater " + id++);
            }
        });

        this.renderingTarget.setup(this);

        isSetUp = true;
    }

    public R getRenderingTarget() {
        return renderingTarget;
    }

    private <T extends PipelinedExecutor> void setupPipelineOfElements(Set<T> allAvailable, List<T> dest, String elementType) {
        final List<T> elements = new ArrayList<>();

        final Set<String> categories = new HashSet<>();

        for (T t : allAvailable) {
            categories.add(t.getCategory());
        }

        categories.forEach((category) -> {
            //Find the best renderer:
            T bestElement = null;
            for (T r : allAvailable) {
                if (r.isAvailable(renderingTarget) && category.equals(r.getCategory())
                        && (bestElement == null || bestElement.getPreferenceInCategory() < r.getPreferenceInCategory())) {
                    bestElement = r;
                }
            }

            if (bestElement != null) {
                elements.add(bestElement);
                System.out.println("Using best available " + elementType + " '" + bestElement.getName() + "' for category " + category);
            } else {
                System.out.println("No available " + elementType + " for category " + category);
            }
        });

        dest.clear();
        dest.addAll(elements);
        Collections.sort(dest, new PipelinedExecutor.Comparator());
    }

    private void setupRenderersPipeline() {
        setupPipelineOfElements(allRenderers, renderersPipeline, "Renderer");
    }

    private void setupWorldUpdatersPipeline() {
        setupPipelineOfElements(allUpdaters, updatersPipeline, "WorldUpdater");
    }

    private void setupInputListenersPipeline() {
        setupPipelineOfElements(allInuptListeners, inputListenersPipeline, "InputListener");
    }

    public void addInputListener(InputListener<R, I> listener) {
        allInuptListeners.add(listener);
    }

    public boolean hasInputListener(InputListener<R, I> listener) {
        return allInuptListeners.contains(listener);
    }

    public boolean removeInputListener(InputListener<R, I> listener) {
        return allInuptListeners.remove(listener);
    }

    public Set<InputListener<R, I>> getAllInputListeners() {
        return Collections.unmodifiableSet(allInuptListeners);
    }

    public List<InputListener<R, I>> getInputListenersPipeline() {
        return Collections.unmodifiableList(inputListenersPipeline);
    }

    public void addRenderer(Renderer<R> renderer) {
        if (renderer != null) {
            allRenderers.add(renderer);
        }
    }

    public boolean hasRenderer(Renderer<R> renderer) {
        return allRenderers.contains(renderer);
    }

    public boolean removeRenderer(Renderer<R> renderer) {
        return allRenderers.remove(renderer);
    }

    public Set<Renderer<R>> getAllRenderers() {
        return Collections.unmodifiableSet(allRenderers);
    }

    public List<Renderer<R>> getRenderersPipeline() {
        //TODO: check initialized
        return Collections.unmodifiableList(renderersPipeline);
    }

    public boolean isRendererInPipeline(Renderer<R> renderer) {
        return renderersPipeline.contains(renderer);
    }

    public void addWorldUpdater(WorldUpdater<R> updater) {
        if (updater != null) {
            allUpdaters.add(updater);
        }
    }

    public boolean hasWorldUpdater(WorldUpdater<R> updater) {
        return allUpdaters.contains(updater);
    }

    public boolean removeWorldUpdater(WorldUpdater<R> updater) {
        return allUpdaters.remove(updater);
    }

    public Set<WorldUpdater<R>> getAllWorldUpdaters() {
        return Collections.unmodifiableSet(allUpdaters);
    }

    public List<WorldUpdater<R>> getWorldUpdatersPipeline() {
        //TODO: check initialized
        return Collections.unmodifiableList(updatersPipeline);
    }

    public boolean isWorldUpdaterInPipeline(WorldUpdater<R> renderer) {
        return updatersPipeline.contains(renderer);
    }

    public Vector2fc getTranslate() {
        return translate;
    }

    public Vector2f getTranslate(Vector2f dest) {
        return dest.set(translate);
    }

    public void setTranslate(float x, float y) {
        translate.set(x, y);
        loadModelViewProjection();
    }

    public void setTranslate(Vector2fc value) {
        translate.set(value);
        loadModelViewProjection();
    }

    public void translate(float x, float y) {
        translate.add(x, y);
        loadModelViewProjection();
    }

    public void translate(Vector2fc value) {
        translate.add(value);
        loadModelViewProjection();
    }

    public float getZoom() {
        return zoom;
    }

    public void setZoom(float zoom) {
        this.zoom = zoom;
        loadModelViewProjection();
    }

    public float aspectRatio() {
        return (float) this.width / this.height;
    }

    public void centerOn(Vector2fc center, float width, float height) {
        setTranslate(-center.x(), -center.y());

        if (width > 0 && height > 0) {
            final Rect2D visibleRange = getViewBoundaries();
            final float zoomFactor = Math.max(width / visibleRange.width(), height / visibleRange.height());

            zoom /= zoomFactor;
        }

        loadModelViewProjection();
    }

    private void loadModelViewProjection() {
        loadModel();
        loadView();
        loadProjection();

        projectionMatrix.mulAffine(viewMatrix, modelViewProjectionMatrix);
        modelViewProjectionMatrix.mulAffine(modelMatrix);

        modelViewProjectionMatrix.get(modelViewProjectionMatrixFloats);
        modelViewProjectionMatrix.invertAffine(modelViewProjectionMatrixInverted);

        calculateWorldBoundaries();
    }

    private void loadModel() {
        //Always identity at the moment
    }

    private void loadView() {
        viewMatrix.scaling(zoom, zoom, 1f);
        viewMatrix.translate(translate.x, translate.y, 0);
    }

    private void loadProjection() {
        projectionMatrix.setOrtho2D(-width / 2, width / 2, -height / 2, height / 2);
    }

    private void calculateWorldBoundaries() {
        final Vector3f minCoords = new Vector3f();
        final Vector3f maxCoords = new Vector3f();

        modelViewProjectionMatrixInverted.transformAab(-1, -1, 0, 1, 1, 0, minCoords, maxCoords);

        viewBoundaries = new Rect2D(minCoords.x, minCoords.y, maxCoords.x, maxCoords.y);
    }

    public void reshape(int width, int height) {
        this.width = width;
        this.height = height;

        loadModelViewProjection();
    }

    public synchronized void start() {
        if (!isSetUp) {
            setup();
        }
        renderingTarget.start();
    }

    public synchronized void initPipeline() {
        setupRenderersPipeline();
        setupWorldUpdatersPipeline();
        setupInputListenersPipeline();

        updatersPipeline.forEach((worldUpdater) -> {
            worldUpdater.init(renderingTarget);
        });

        renderersPipeline.forEach((renderer) -> {
            renderer.init(renderingTarget);
        });

        loadModelViewProjection();
    }

    public synchronized void stop() {
        try {
            updatersThreadPool.shutdown();
            boolean terminated = updatersThreadPool.awaitTermination(DEFAULT_MAX_WORLD_UPDATES_PER_SECOND, TimeUnit.SECONDS);
            if (!terminated) {
                updatersThreadPool.shutdownNow();
            }
        } catch (InterruptedException ex) {
            ex.printStackTrace();
            //NOOP
        }

        System.out.println("Dispose updaters");
        updatersPipeline.forEach((worldUpdater) -> {
            worldUpdater.dispose(renderingTarget);
        });

        System.out.println("Dispose renderers");
        renderersPipeline.forEach((renderer) -> {
            renderer.dispose(renderingTarget);
        });

        this.renderingTarget.stop();
    }

    private CompletableFuture allUpdatersCompletableFuture = null;

    private CompletableFuture<WorldUpdater> completableFutureOfUpdater(final WorldUpdater updater) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                updater.updateWorld();
            } catch (Throwable t) {
                t.printStackTrace();//TODO Logger
            }
            return updater;
        }, updatersThreadPool);
    }

    public void display() {
        renderingTarget.frameStart();
        processInputEvents();

        //Notify renderers when next world data update is done:
        final boolean worldUpdateDone = allUpdatersCompletableFuture != null && allUpdatersCompletableFuture.isDone();
        if (worldUpdateDone) {
            allUpdatersCompletableFuture = null;

            for (Renderer renderer : renderersPipeline) {
                renderer.worldUpdated(renderingTarget);
            }
        }

        //Call renderers for the current frame:
        for (RenderingLayer layer : RenderingLayer.values()) {
            for (Renderer renderer : renderersPipeline) {
                if (renderer.getLayers().contains(layer)) {
                    renderer.render(renderingTarget, layer);
                }
            }
        }

        //Schedule next world update:
        if (!updatersThreadPool.isShutdown() && allUpdatersCompletableFuture == null) {
            //Control max world updates per second
            if (maxWorldUpdatesPerSecond >= 1) {
                if (System.currentTimeMillis() < lastWorldUpdateMillis + 1000 / maxWorldUpdatesPerSecond) {
                    //Skip world update
                    return;
                }
            }

            final CompletableFuture[] futures = new CompletableFuture[updatersPipeline.size()];
            for (int i = 0; i < futures.length; i++) {
                final WorldUpdater worldUpdater = updatersPipeline.get(i);
                futures[i] = completableFutureOfUpdater(worldUpdater);
            }

            allUpdatersCompletableFuture = CompletableFuture.allOf(futures);

            lastWorldUpdateMillis = System.currentTimeMillis();
        }
        renderingTarget.frameEnd();
    }

    private long lastWorldUpdateMillis = 0;

    public Lookup getLookup() {
        return lookup;
    }

    public void addToLookup(Object instance) {
        instanceContent.add(instance);
    }

    public void removeFromLookup(Object instance) {
        instanceContent.remove(instance);
    }

    public GraphModel getGraphModel() {
        return graphModel;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public Matrix4fc getModelMatrix() {
        return modelMatrix;
    }

    public Matrix4fc getViewMatrix() {
        return viewMatrix;
    }

    public Matrix4fc getProjectionMatrix() {
        return projectionMatrix;
    }

    public Matrix4fc getModelViewProjectionMatrix() {
        return modelViewProjectionMatrix;
    }

    public Matrix4fc getModelViewProjectionMatrixInverted() {
        return modelViewProjectionMatrixInverted;
    }

    public Rect2D getViewBoundaries() {
        return viewBoundaries;
    }

    public float[] getBackgroundColor(float[] backgroundColorFloats) {
        System.arraycopy(this.backgroundColor, 0, backgroundColorFloats, 0, 4);
        return backgroundColorFloats;
    }

    public float[] getBackgroundColor() {
        return Arrays.copyOf(backgroundColor, backgroundColor.length);
    }

    public void setBackgroundColor(Color color) {
        float[] backgroundColorComponents = new float[4];
        color.getRGBComponents(backgroundColorComponents);

        setBackgroundColor(backgroundColorComponents);
    }

    public void setBackgroundColor(float[] color) {
        if (color.length != 4) {
            throw new IllegalArgumentException("Expected 4 float RGBA color");
        }

        System.arraycopy(color, 0, backgroundColor, 0, 4);
    }

    public int getMaxWorldUpdatesPerSecond() {
        return maxWorldUpdatesPerSecond;
    }

    public void setMaxWorldUpdatesPerSecond(int maxWorldUpdatesPerSecond) {
        this.maxWorldUpdatesPerSecond = maxWorldUpdatesPerSecond;
    }

    public float[] getModelViewProjectionMatrixFloats(float[] mvpFloats) {
        modelViewProjectionMatrix.get(mvpFloats);
        return mvpFloats;
    }

    public float[] getModelViewProjectionMatrixFloats() {
        return Arrays.copyOf(modelViewProjectionMatrixFloats, modelViewProjectionMatrixFloats.length);
    }

    public Vector2f screenCoordinatesToWorldCoordinates(int x, int y) {
        return screenCoordinatesToWorldCoordinates(x, y, new Vector2f());
    }

    public Vector2f screenCoordinatesToWorldCoordinates(int x, int y, Vector2f dest) {
        final float halfWidth = width / 2.0f;
        final float halfHeight = height / 2.0f;

        float xScreenNormalized = (-halfWidth + x) / halfWidth;
        float yScreenNormalized = (halfHeight - y) / halfHeight;

        final Vector3f worldCoordinates = new Vector3f();
        modelViewProjectionMatrixInverted.transformProject(xScreenNormalized, yScreenNormalized, 0, worldCoordinates);

        return dest.set(worldCoordinates.x, worldCoordinates.y);
    }

    public Vector2f worldCoordinatesToScreenCoordinates(float x, float y) {
        return worldCoordinatesToScreenCoordinates(x, y, new Vector2f());
    }

    public Vector2f worldCoordinatesToScreenCoordinates(float x, float y, Vector2f dest) {
        final Vector3f screenCoordinates = new Vector3f();
        modelViewProjectionMatrix.transformProject(x, y, 0, screenCoordinates);

        return dest.set(screenCoordinates.x, screenCoordinates.y);
    }

    public Vector2f worldCoordinatesToScreenCoordinates(Vector2fc worldCoordinates) {
        return worldCoordinatesToScreenCoordinates(worldCoordinates, new Vector2f());
    }

    public Vector2f worldCoordinatesToScreenCoordinates(Vector2fc worldCoordinates, Vector2f dest) {
        final Vector3f screenCoordinates = new Vector3f();
        modelViewProjectionMatrix.transformProject(worldCoordinates.x(), worldCoordinates.y(), 0, screenCoordinates);

        return dest.set(screenCoordinates.x, screenCoordinates.y);
    }

    private void processInputEvents() {
        for (InputListener<R, I> inputListener : inputListenersPipeline) {
            inputListener.frameStart();
        }

        final Object[] events = eventsQueue.toArray();
        eventsQueue.clear();

        for (Object event : events) {
            for (InputListener<R, I> inputListener : inputListenersPipeline) {
                final boolean consumed = inputListener.processEvent((I) event);
                if (consumed) {
                    break;
                }
            }
        }

        for (InputListener<R, I> inputListener : inputListenersPipeline) {
            inputListener.frameEnd();
        }
    }

    public void queueEvent(I e) {
        eventsQueue.add(e);
    }
}