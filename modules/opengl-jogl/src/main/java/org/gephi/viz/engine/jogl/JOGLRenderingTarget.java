package org.gephi.viz.engine.jogl;

import com.jogamp.newt.event.KeyEvent;
import com.jogamp.newt.event.MouseEvent;
import com.jogamp.newt.event.NEWTEvent;
import com.jogamp.newt.event.awt.AWTKeyAdapter;
import com.jogamp.newt.event.awt.AWTMouseAdapter;
import com.jogamp.newt.opengl.GLWindow;
import com.jogamp.opengl.GL;
import static com.jogamp.opengl.GL.GL_COLOR_BUFFER_BIT;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.awt.GLJPanel;
import com.jogamp.opengl.util.Animator;
import org.gephi.viz.engine.VizEngine;
import org.gephi.viz.engine.jogl.util.gl.capabilities.GLCapabilitiesSummary;
import org.gephi.viz.engine.jogl.util.gl.capabilities.Profile;
import org.gephi.viz.engine.spi.RenderingTarget;

/**
 *
 * @author Eduardo Ramos
 */
public class JOGLRenderingTarget implements RenderingTarget, GLEventListener, com.jogamp.newt.event.KeyListener, com.jogamp.newt.event.MouseListener {

    private final GLAutoDrawable drawable;

    //Animators
    private Animator animator;
    private VizEngine<JOGLRenderingTarget, NEWTEvent> engine;

    public JOGLRenderingTarget(GLAutoDrawable drawable) {
        this.drawable = drawable;
    }

    @Override
    public void setup(VizEngine engine) {
        this.engine = engine;

        drawable.addGLEventListener(this);

        if (drawable instanceof GLWindow) {
            setup((GLWindow) drawable);
        } else if (drawable instanceof GLJPanel) {
            setup((GLJPanel) drawable);
        } else if (drawable instanceof GLCanvas) {
            setup((GLCanvas) drawable);
        } else {
            System.out.println(drawable.getClass() + " event bridge not supported yet. Be sure to manually setup your events listener");
        }
    }

    private void setup(GLWindow gLWindow) {
        gLWindow.addKeyListener(this);
        gLWindow.addMouseListener(this);
    }

    private void setup(GLJPanel glJpanel) {
        new AWTKeyAdapter(this, glJpanel).addTo(glJpanel);
        new AWTMouseAdapter(this, glJpanel).addTo(glJpanel);
    }

    private void setup(GLCanvas glCanvas) {
        new AWTKeyAdapter(this, glCanvas).addTo(glCanvas);
        new AWTMouseAdapter(this, glCanvas).addTo(glCanvas);
    }

    public GLAutoDrawable getDrawable() {
        return drawable;
    }

    @Override
    public void start() {
        if (animator != null) {
            throw new IllegalStateException("Call stop first!");
        }

        animator = new Animator();
        animator.add(drawable);
        animator.setRunAsFastAsPossible(false);
        animator.setExclusiveContext(false);
        animator.setUpdateFPSFrames(300, System.out);

        animator.start();
    }

    @Override
    public void stop() {
        if (animator == null) {
            throw new IllegalStateException("Call start first!");
        }
        animator.stop();
        animator = null;
    }

    @Override
    public void init(GLAutoDrawable drawable) {
        final GL gl = drawable.getGL();
        
        final GLCapabilitiesSummary capabilities = new GLCapabilitiesSummary(gl, Profile.CORE);
        engine.addToLookup(capabilities);

        gl.setSwapInterval(0);//Disable Vertical synchro

        gl.glDisable(GL.GL_DEPTH_TEST);//Z-order is set by the order of drawing

        //Disable blending for better performance
        gl.glDisable(GL.GL_BLEND);
        
        engine.initPipeline();
    }

    @Override
    public void dispose(GLAutoDrawable drawable) {
        //NOOP
    }

    private final float backgroundColor[] = new float[4];

    @Override
    public void display(GLAutoDrawable drawable) {
        final GL gl = drawable.getGL().getGL();

        engine.getBackgroundColor(backgroundColor);
        gl.glClearColor(backgroundColor[0], backgroundColor[1], backgroundColor[2], backgroundColor[3]);
        gl.glClear(GL_COLOR_BUFFER_BIT);

        engine.display();
    }

    @Override
    public void reshape(GLAutoDrawable drawable, int x, int y, int width, int height) {
        engine.reshape(width, height);
    }

    @Override
    public void keyPressed(KeyEvent e) {
        engine.queueEvent(e);
    }

    @Override
    public void keyReleased(KeyEvent e) {
        engine.queueEvent(e);
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        engine.queueEvent(e);
    }

    @Override
    public void mouseEntered(MouseEvent e) {
        engine.queueEvent(e);
    }

    @Override
    public void mouseExited(MouseEvent e) {
        engine.queueEvent(e);
    }

    @Override
    public void mousePressed(MouseEvent e) {
        engine.queueEvent(e);
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        engine.queueEvent(e);
    }

    @Override
    public void mouseMoved(MouseEvent e) {
        engine.queueEvent(e);
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        engine.queueEvent(e);
    }

    @Override
    public void mouseWheelMoved(MouseEvent e) {
        engine.queueEvent(e);
    }

}