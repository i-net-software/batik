/*

 ============================================================================
                   The Apache Software License, Version 1.1
 ============================================================================

 Copyright (C) 1999-2003 The Apache Software Foundation. All rights reserved.

 Redistribution and use in source and binary forms, with or without modifica-
 tion, are permitted provided that the following conditions are met:

 1. Redistributions of  source code must  retain the above copyright  notice,
    this list of conditions and the following disclaimer.

 2. Redistributions in binary form must reproduce the above copyright notice,
    this list of conditions and the following disclaimer in the documentation
    and/or other materials provided with the distribution.

 3. The end-user documentation included with the redistribution, if any, must
    include  the following  acknowledgment:  "This product includes  software
    developed  by the  Apache Software Foundation  (http://www.apache.org/)."
    Alternately, this  acknowledgment may  appear in the software itself,  if
    and wherever such third-party acknowledgments normally appear.

 4. The names "Batik" and  "Apache Software Foundation" must  not  be
    used to  endorse or promote  products derived from  this software without
    prior written permission. For written permission, please contact
    apache@apache.org.

 5. Products  derived from this software may not  be called "Apache", nor may
    "Apache" appear  in their name,  without prior written permission  of the
    Apache Software Foundation.

 THIS SOFTWARE IS PROVIDED ``AS IS'' AND ANY EXPRESSED OR IMPLIED WARRANTIES,
 INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 FITNESS  FOR A PARTICULAR  PURPOSE ARE  DISCLAIMED.  IN NO  EVENT SHALL  THE
 APACHE SOFTWARE  FOUNDATION  OR ITS CONTRIBUTORS  BE LIABLE FOR  ANY DIRECT,
 INDIRECT, INCIDENTAL, SPECIAL,  EXEMPLARY, OR CONSEQUENTIAL  DAMAGES (INCLU-
 DING, BUT NOT LIMITED TO, PROCUREMENT  OF SUBSTITUTE GOODS OR SERVICES; LOSS
 OF USE, DATA, OR  PROFITS; OR BUSINESS  INTERRUPTION)  HOWEVER CAUSED AND ON
 ANY  THEORY OF LIABILITY,  WHETHER  IN CONTRACT,  STRICT LIABILITY,  OR TORT
 (INCLUDING  NEGLIGENCE OR  OTHERWISE) ARISING IN  ANY WAY OUT OF THE  USE OF
 THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 This software  consists of voluntary contributions made  by many individuals
 on  behalf of the Apache Software  Foundation. For more  information on the
 Apache Software Foundation, please see <http://www.apache.org/>.

*/

package org.apache.batik.script.rhino;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.Writer;
import java.lang.reflect.Method;
import java.net.URL;
import java.security.AccessControlContext;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;

import org.apache.batik.bridge.InterruptedBridgeException;
import org.apache.batik.script.Interpreter;
import org.apache.batik.script.InterpreterException;
import org.apache.batik.script.Window;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Function;
import org.mozilla.javascript.JavaScriptException;
import org.mozilla.javascript.NativeJavaPackage;
import org.mozilla.javascript.PropertyException;
import org.mozilla.javascript.Script;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;
import org.mozilla.javascript.SecurityController;
import org.mozilla.javascript.WrapFactory;
import org.mozilla.javascript.WrappedException;
import org.w3c.dom.events.EventTarget;

/**
 * A simple implementation of <code>Interpreter</code> interface to use
 * Rhino ECMAScript interpreter.
 * @author <a href="mailto:cjolif@ilog.fr">Christophe Jolif</a>
 * @version $Id$
 */
public class RhinoInterpreter implements Interpreter {
    private static String[] TO_BE_IMPORTED = {
        "java.lang",
        "org.w3c.dom",
        "org.w3c.dom.css",
        "org.w3c.dom.events",
        "org.w3c.dom.smil",
        "org.w3c.dom.stylesheets",
        "org.w3c.dom.svg",
        "org.w3c.dom.views"
    };

    /**
     * The window object
     */
    protected Window window;

    public Window getWindow() {
        return window;
    }


    private static class Entry {
        String str;
        Script script;
        Entry(String str, Script script) {
            this.str = str;
            this.script = script;
        }
    }

    /**
     * store last 32 precompiled objects.
     */
    private static final int MAX_CACHED_SCRIPTS = 32;

    /**
     * Constant used to describe an SVG source
     */
    public static final String SOURCE_NAME_SVG = "<SVG>";

    /**
     * Name of the "window" object when referenced by scripts
     */
    public static final String BIND_NAME_WINDOW = "window";

    private ScriptableObject globalObject = null;
    private LinkedList compiledScripts = new LinkedList();
    private WrapFactory wrapFactory =
        new BatikWrapFactory(this);

    /**
     * The Rhino 'security domain'. We use the RhinoClassLoader
     * which will grant permissions to connect to the document
     * URL.
     */
    protected RhinoClassLoader rhinoClassLoader;

    /**
     * The SecurityController implementation for Batik,
     * which ensures scripts have access to the
     * server they were downloaded from
     */
    private SecurityController securityController
        = new BatikSecurityController();

    /**
     * Build a <code>Interpreter</code> for ECMAScript using Rhino.
     *
     * @param documentURL the URL for the document which references
     *
     * @see org.apache.batik.script.Interpreter
     * @see org.apache.batik.script.InterpreterPool
     */
    public RhinoInterpreter(URL documentURL) {
        try { 
            rhinoClassLoader = new RhinoClassLoader
                (documentURL, getClass().getClassLoader());
        } catch (SecurityException se) {
            rhinoClassLoader = null;
        }
        // entering a context
        Context ctx = enterContext();
        try {
            try {
                Scriptable scriptable = ctx.initStandardObjects(null, false);
                ScriptableObject.defineClass(scriptable, WindowWrapper.class);
            } catch (Exception e) {
                // cannot happen
            }
            // we now have the window object as the global object from the
            // launch of the interpreter.
            // 1. it works around a Rhino bug introduced in 15R4 (but fixed
            // by a later patch).
            // 2. it sounds cleaner.
            WindowWrapper wWrapper = new WindowWrapper(ctx);
            globalObject = wWrapper;
            // import Java lang package & DOM Level 2 & SVG DOM packages
            NativeJavaPackage[] p= new NativeJavaPackage[TO_BE_IMPORTED.length];
            for (int i = 0; i < TO_BE_IMPORTED.length; i++) {
                p[i] = new NativeJavaPackage(TO_BE_IMPORTED[i], rhinoClassLoader);
            } try {
              ScriptableObject.callMethod(globalObject, "importPackage", p);
            } catch (JavaScriptException e) {
              // cannot happen as we know the method is there and
              // the parameters are ok
            }
        } finally {
            Context.exit();
        }
    }

    /**
     * Returns the AccessControlContext associated with this Interpreter.
     * @see org.apache.batik.script.rhino.RhinoClassLoader
     */
    public AccessControlContext getAccessControlContext(){
        return rhinoClassLoader.getAccessControlContext();
    }

    /**
     * Implementation helper. Makes sure the proper security is set
     * on the context.
     */
    public Context enterContext(){
        Context ctx = Context.getCurrentContext();
        if (ctx == null) {
            ctx = new ExtendedContext();
            ctx.setWrapFactory(wrapFactory);
            ctx.setSecurityController(securityController);
        }
        ctx = Context.enter(ctx);

        return ctx;
    }

    /**
     * This method returns the ECMAScript global object used by this interpreter.
     */
    protected ScriptableObject getGlobalObject() {
        return globalObject;
    }

    // org.apache.batik.script.Intepreter implementation

    /**
     * This method evaluates a piece of ECMAScript.
     * @param scriptreader a <code>java.io.Reader</code> on the piece of script
     * @return if no exception is thrown during the call, should return the
     * value of the last expression evaluated in the script.
     */
    public Object evaluate(Reader scriptreader)
        throws InterpreterException, IOException {
        return evaluate(scriptreader, SOURCE_NAME_SVG);
    }

    /**
     * This method evaluates a piece of ECMAScript.
     * @param scriptreader a <code>java.io.Reader</code> on the piece of script
     * @param description description which can be later used (e.g., for error
     *        messages).
     * @return if no exception is thrown during the call, should return the
     * value of the last expression evaluated in the script.
     */
    public Object evaluate(Reader scriptreader, String description)
        throws InterpreterException, IOException {

        Object rv = null;
        final Context ctx = enterContext();
        try {
            rv = ctx.evaluateReader(globalObject,
                                    scriptreader,
                                    description,
                                    1, rhinoClassLoader);
        } catch (JavaScriptException e) {
            // exception from JavaScript (possibly wrapping a Java Ex)
            if (e.getValue() instanceof Exception) {
                Exception ex = (Exception)e.getValue();
                throw new InterpreterException(ex, ex.getMessage(), -1, -1);
            } else
                throw new InterpreterException(e, e.getMessage(), -1, -1);
        } catch (WrappedException we) {
            // main Rhino RuntimeException
            Throwable w = we.getWrappedException();
            if (w instanceof Exception)
                throw
                    new InterpreterException((Exception)we.getWrappedException(),
                                             we.getWrappedException().getMessage(),
                                             -1, -1);
            else
                throw new InterpreterException(we.getWrappedException().getMessage(), -1, -1);
        } catch (InterruptedBridgeException ibe) {
            // This sometimes happens when script builds stuff.
            throw ibe;
        }  catch (RuntimeException re) {
            // other RuntimeExceptions
            throw new InterpreterException(re, re.getMessage(), -1, -1);
        } finally {
            Context.exit();
        }
        return rv;
    }

    /**
     * This method evaluates a piece of ECMA script.
     * The first time a String is passed, it is compiled and evaluated.
     * At next call, the piece of script will only be evaluated to
     * prevent from recompiling it.
     * @param scriptstr the piece of script
     * @return if no exception is thrown during the call, should return the
     * value of the last expression evaluated in the script.
     */
    public Object evaluate(final String scriptstr)
        throws InterpreterException {
        Object rv = null;
        final Context ctx = enterContext();
        try {
            Script script = null;
            Entry et = null;
            Iterator it = compiledScripts.iterator();
            // between nlog(n) and log(n) because it is
            // an AbstractSequentialList
            while (it.hasNext()) {
                if ((et = (Entry)(it.next())).str.equals(scriptstr)) {
                    // if it is not at the end, remove it because
                    // it will change from place (it is faster
                    // to remove it now)
                    script = et.script;
                    it.remove();
                    break;
                }
            }

            if (script == null) {
                // this script has not been compiled yet or has been forgotten
                // since the compilation:
                // compile it and store it for future use.

                script = (Script)AccessController.doPrivileged
                    (new PrivilegedAction() {
                            public Object run() {
                                try {
                                    return ctx.compileReader
                                        (globalObject,
                                         new StringReader(scriptstr),
                                         SOURCE_NAME_SVG,
                                         1, rhinoClassLoader);
                                } catch (IOException io) {
                                    // Should never happen: using a string
                                    throw new Error();
                                }
                            }
                        });

                if (compiledScripts.size()+1 > MAX_CACHED_SCRIPTS) {
                    // too many cached items - we should delete the
                    // oldest entry.  all of this is very fast on
                    // linkedlist
                    compiledScripts.removeFirst();
                }
                // stroring is done here:
                compiledScripts.addLast(new Entry(scriptstr, script));
            } else {
                // this script has been compiled before,
                // just update it's index so it won't get deleted soon.
                compiledScripts.addLast(et);
            }

            try {
                rv = script.exec(ctx, globalObject);
            } catch (JavaScriptException e) {
                // exception from JavaScript (possibly wrapping a Java Ex)
                if (e.getValue() instanceof Exception) {
                    Exception ex = (Exception)e.getValue();
                    throw new InterpreterException(ex, ex.getMessage(), -1,-1);
                } else
                    throw new InterpreterException(e, e.getMessage(), -1, -1);
            } catch (WrappedException we) {
                // main Rhino RuntimeException
                throw
                    new InterpreterException
                    ((Exception)we.getWrappedException(),
                     we.getWrappedException().getMessage(), -1, -1);
            } catch (RuntimeException re) {
                // other RuntimeExceptions
                throw new InterpreterException(re, re.getMessage(), -1, -1);
            }

        } finally {
            Context.exit();
        }
        return rv;
    }

    /**
     * For <code>RhinoInterpreter</code> this method does nothing.
     */
    public void dispose() {
        Context.setCachingEnabled(false);
        Context.setCachingEnabled(true);
    }

    /**
     * This method registers a particular Java <code>Object</code> in
     * the environment of the interpreter.
     * @param name the name of the script object to create
     * @param object the Java object
     */
    public void bindObject(String name, Object object) {
        enterContext();
        try {
            if (name.equals(BIND_NAME_WINDOW) && object instanceof Window) {
                window = (Window)object;
                object = globalObject;
            }
            try {
                Scriptable jsObject;
                jsObject = Context.toObject(object, globalObject);
                objects.put(name, jsObject);
                if (ScriptableObject.getProperty(globalObject, name) ==
                    ScriptableObject.NOT_FOUND)
                    globalObject.defineProperty
                        (name, new RhinoGetDelegate(name),
                         rhinoGetter, null, ScriptableObject.READONLY);
            } catch (PropertyException pe) {
                pe.printStackTrace();
            }
        } finally {
            Context.exit();
        }
    }
    /**
     * HashTable to store properties bounds on the global object.
     * So they don't end up in the JavaMethods static table.
     */
    Map objects = new HashMap(4);

    /**
     * Class to act as 'get' delegate for Rhino.  This uses the
     * currentContext to get the current Interpreter object which
     * allows it to lookup the object requested.  This gets around the
     * fact that the global object gets referenced from a static
     * context but the Context does not.
     */
    public static class RhinoGetDelegate {
        String name;
        RhinoGetDelegate(String name) {
            this.name = name;
        }
        public Object get(ScriptableObject so) {
            Context ctx = Context.getCurrentContext();
            if (ctx == null ) return null;
            return ((ExtendedContext)ctx).getInterpreter().objects.get(name);
        }
    }
    // The method to use for getting the value from the
    // RhinoGetDelegate.
    static Method rhinoGetter;
    static {
        try {
            Class [] getterArgs = { ScriptableObject.class };
            rhinoGetter = RhinoGetDelegate.class.getDeclaredMethod
                ("get", getterArgs);
        } catch (NoSuchMethodException nsm) { }
    }


    /**
     * To be used by <code>EventTargetWrapper</code>.
     */
    void callHandler(Function handler,
                     Object arg)
        throws JavaScriptException {
        Context ctx = enterContext();
        try {
            arg = Context.toObject(arg, globalObject);
            Object[] args = {arg};
            handler.call(ctx, globalObject, globalObject, args);
        } finally {
            Context.exit();
        }
    }

    /**
     * To be used by <code>WindowWrapper</code>.
     */
    void callMethod(ScriptableObject obj,
                    String methodName,
                    ArgumentsBuilder ab)
        throws JavaScriptException {
        enterContext();
        try {
            ScriptableObject.callMethod(obj, methodName, ab.buildArguments());
        } finally {
            Context.exit();
        }
    }

    /**
     * To be used by <code>WindowWrapper</code>.
     */
    void callHandler(Function handler,
                     Object[] args)
        throws JavaScriptException {
        Context ctx = enterContext();
        try {
            handler.call(ctx, globalObject, globalObject, args);
        } finally {
            Context.exit();
        }
    }

    /**
     * To be used by <code>WindowWrapper</code>.
     */
    void callHandler(Function handler, ArgumentsBuilder ab)
        throws JavaScriptException {
        Context ctx = enterContext();
        try {
            Object [] args = ab.buildArguments();
           handler.call(ctx, handler.getParentScope(), globalObject, args );
        } finally {
            Context.exit();
        }
    }

    /**
     * To build an argument list.
     */
    public interface ArgumentsBuilder {
        Object[] buildArguments();
    }

    /**
     * Build the wrapper for objects implement <code>EventTarget</code>.
     */
    Scriptable buildEventTargetWrapper(EventTarget obj) {
        return new EventTargetWrapper(globalObject, obj);
    }

    /**
     * By default Rhino has no output method in its language. That's why
     * this method does nothing.
     * @param output the new out <code>Writer</code>.
     */
    public void setOut(Writer out) {
        // no implementation of a default output function in Rhino
    }

    // org.apache.batik.i18n.Localizable implementation

    /**
     * Provides a way to the user to specify a locale which override the
     * default one. If null is passed to this method, the used locale
     * becomes the global one.
     * @param l The locale to set.
     */
    public Locale getLocale() {
        // <!> TODO : in Rhino the local is for a thread not a scope..
        return null;
    }

    /**
     * Returns the current locale or null if the locale currently used is
     * the default one.
     */
    public void setLocale(Locale locale) {
        // <!> TODO : in Rhino the local is for a thread not a scope..
    }

    /**
     * Creates and returns a localized message, given the key of the message, 0, data.length
     * in the resource bundle and the message parameters.
     * The messages in the resource bundle must have the syntax described in
     * the java.text.MessageFormat class documentation.
     * @param key  The key used to retreive the message from the resource
     *             bundle.
     * @param args The objects that compose the message.
     * @exception MissingResourceException if the key is not in the bundle.
     */
    public String formatMessage(String key, Object[] args) {
        return null;
    }

    public class ExtendedContext extends Context {
        public ExtendedContext() {
            super();
        }

        public RhinoInterpreter getInterpreter() {
            return RhinoInterpreter.this;
        }

        public Window getWindow() {
            return RhinoInterpreter.this.getWindow();
        }

        public ScriptableObject getGlobalObject() {
            return RhinoInterpreter.this.getGlobalObject();
        }
    }
}
