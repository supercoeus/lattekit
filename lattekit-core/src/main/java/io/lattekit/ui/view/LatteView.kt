package io.lattekit.ui.view

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.util.Log
import android.view.View
import android.view.ViewGroup
import io.lattekit.annotation.Prop
import io.lattekit.ui.LatteActivity
import io.lattekit.ui.style.Stylesheet
import java.lang.reflect.Field

/**
 * Created by maan on 2/15/16.
 */
open class LatteView {

    companion object {
        var SAVED_OBJECTS = mutableMapOf<String,LatteView>();
        var PROP_FILEDS = mutableMapOf<String, MutableMap<String,Field>>()

        @JvmStatic
        var LOOKUP_CACHE : MutableMap<String,Class<out Any>>  = mutableMapOf(
                "View" to View::class.java,
                "TextView" to android.widget.TextView::class.java,
                "ImageView" to ImageView::class.java,
                "ListView" to ListView::class.java,
                "LinearLayout" to LinearLayout::class.java,
                "RelativeLayout" to RelativeLayout::class.java,
                "ViewPager" to ViewPager::class.java,
                "WebView" to android.webkit.WebView::class.java
        )

        @JvmStatic
        fun props(vararg objects : Any?) : MutableMap<String,Any?> {
            var map = mutableMapOf<String,Any?>()
            for (i in 0..objects.size-1 step 2) {
                map.put(objects.get(i) as String, objects.get(i+1))
            }
            return map;
        }

        @JvmStatic
        fun getSavedObject(id : String) : LatteView? {
            return SAVED_OBJECTS.get(id)
        }

        @JvmStatic
        fun log(message : String) {
            Log.d("Latte",message)
        }

        @JvmStatic
        fun createLayout( viewType : String , props: MutableMap<String,Any?> ) : LatteView {
            return createLayout(mutableListOf(), viewType, props, ChildrenProc { mutableListOf() })
        }

        @JvmStatic
        fun createLayout(viewType : String , props: MutableMap<String,Any?> , childrenProc: ChildrenProc) : LatteView {
            return createLayout(mutableListOf(), viewType, props, childrenProc)
        }

        @JvmStatic
        fun createLayout(imports : MutableList<String>, vT: String , props: MutableMap<String,Any?>, childrenProc: ChildrenProc) : LatteView {
            var layout : LatteView? = null;
            var viewType = vT;
            var cachedCls: Class<*>? = LOOKUP_CACHE.get(vT);
            var clazz = if ( cachedCls != null ) {
                cachedCls
            } else if (vT.contains(".")) {
                var cls =  try {
                    var cls = Class.forName(vT+"Impl")
                    if (!LatteView::class.java.isAssignableFrom(cls)) {
                        cls = Class.forName(vT)
                    }
                    cls
                } catch (ex: ClassNotFoundException ){
                    Class.forName(vT)
                }
                LOOKUP_CACHE.put(vT,cls);
                cls
            } else {
                var cls = Class.forName("android.widget."+vT);
                LOOKUP_CACHE.put(vT,cls);
                cls
            }
            log("Found class " + clazz)
            if (ViewGroup::class.java.isAssignableFrom(clazz)) {
                layout = NativeViewGroup();
                (layout as NativeViewGroup).nativeViewClass = clazz as Class<out View>
                viewType = clazz.name
            } else if (View::class.java.isAssignableFrom(clazz)) {
                layout = NativeView();
                (layout as NativeView).nativeViewClass = clazz as Class<out View>
                viewType = clazz.name
            } else {
                layout = clazz.newInstance() as LatteView;
                viewType = clazz.name
            }

            layout.viewType = viewType;
            layout.props = props;
            layout.childrenProc = childrenProc;
            layout.children = layout.childrenProc?.apply() as MutableList<LatteView>

            return layout
        }


    }

    var viewType : String? = null;
    var renderedViews: MutableList<LatteView> = mutableListOf()
    var androidView: View? = null

    var props : MutableMap<String,Any?> = mutableMapOf()
    var parentView : LatteView? = null

    var children = mutableListOf<LatteView>()

    var stylesheet : Stylesheet = Stylesheet();
    var objectId : String? = null;
    var activity : Activity? = null
    var childrenProc : ChildrenProc? = null
    var isMounted : Boolean = false;

    val propFields : MutableMap<String,Field>
        get() {
            var map = PROP_FILEDS[this.javaClass.name]
            if (map == null) {
                val newMap = mutableMapOf<String,Field>()
                var cls: Class<in Object> = this.javaClass
                while (cls != Object::class.java) {
                    cls.declaredFields.forEach { f ->
                        if (f.isAnnotationPresent(Prop::class.java)) {
                            var anno = f.getAnnotation(Prop::class.java);
                            var name: String = if (anno.value != "") anno.value else f.name;
                            f.setAccessible(true);
                            newMap.put(name, f)
                        }
                    }
                    cls = cls.superclass
                }
                PROP_FILEDS.put(this.javaClass.name,newMap)
                return newMap
            }
            return map!!
        }


    var rootAndroidView : View? = null
        get() {
            if (this.androidView != null) {
                return this.androidView
            } else if (this.renderedViews.get(0) != null) {
                return this.renderedViews.get(0).rootAndroidView;
            }
            return null
        }


    var id : Int = 0
        get() = this.props.get("id") as Int

    fun buildView(activity: Activity, lp : ViewGroup.LayoutParams) : View {
        this.activity = activity;
        this.renderTree()
        this.buildAndroidViewTree(activity,lp);
        return this.rootAndroidView!!;
    }

    fun notifyMounted() {
        isMounted = true;
        findRefs(this.renderedViews);
        onViewMounted();
    }

    fun onStateChanged() {
        handleStateChanged()
    }

    fun handleStateChanged() {
        this.renderTree()
        this.buildAndroidViewTree(activity as Context, rootAndroidView?.layoutParams!!);
    }


    fun show(caller : LatteView) {
        var myId = "${System.currentTimeMillis()}";
        var intent = Intent(caller.rootAndroidView?.context, LatteActivity::class.java);
        intent.putExtra("_LATTE_KIT_OBJ_ID",myId)
        LatteView.SAVED_OBJECTS.put(myId,this)
        caller.rootAndroidView?.context?.startActivity(intent);
    }

    open fun onViewMounted() {
    }

    fun buildAndroidViewTree(a: Context, lp: ViewGroup.LayoutParams) : View {
        // First build my view
        this.activity = a as Activity;
        if (this is NativeView) {
            if (this.androidView == null) {
                this.androidView = this.renderNative(a);
            }
            if (this.androidView?.layoutParams == null) {
                this.androidView?.layoutParams = lp;
            }
            if (!isMounted) {
                notifyMounted();
            }

            if (this is NativeViewGroup) {
                this.mountChildren()
            }
            return this.androidView!!
        } else {
            // If we don't have native android view, then we are virtual node
            var subAndroid =  this.renderedViews[0].buildAndroidViewTree(a, lp);
            if (!isMounted) {
                notifyMounted();
            }
            return subAndroid;
        }
    }

    fun getNonVirtualParent() : NativeView? {
        if (parentView is NativeView) {
            return parentView as NativeView;
        }
        return parentView?.getNonVirtualParent()
    }

    fun copy() : LatteView {
        val copy = this.javaClass.newInstance()
        copy.props = mutableMapOf();
        this.props.forEach { entry -> copy.props.put(entry.key,entry.value) }
        copy.children = mutableListOf()
        children.forEach { copy.children.add(it.copy()) }
        copy.viewType = viewType
        copy.stylesheet = stylesheet;
        return copy;
    }

    fun findRefs(subViews : List<LatteView>) {
        subViews.forEach { it : LatteView ->
            var ref : String? = it.props.get("ref") as String?
            if ( ref != null ) {
                val fieldName = ref;
                var field = this.javaClass.getDeclaredFields().find { f -> f.name == fieldName}
                if (field != null) {
                    field.setAccessible(true);
                    if (field.getType().isAssignableFrom(it.javaClass)) {
                        field.set(this, it);
                    } else if (it.androidView != null && field.getType().isAssignableFrom(it.androidView?.javaClass)) {
                        field.set(this, it.androidView);
                    }
                } else {
                    log("Couldn't find field " + fieldName)
                }
            }
            this.findRefs(it.children)
        }
    }



    open fun  injectProps() {
        log("${this} I have props fields ${propFields.map{"${it.key}"}.joinToString(",")} ::: ${this.javaClass.declaredFields.map{"${it.name} : ${it.isAnnotationPresent(Prop::class.java)}"}.joinToString(",")}")
        propFields.forEach{ it ->
            if (!this.props.containsKey(it.key)) {
                // Remove deleted property
                var field = propFields.get(it.key)
                field?.set(this, null)
            }
        }

        this.props?.forEach { entry ->
            var field : Field? = this.propFields[entry.key]
            if ( field != null) {
                if (field.getType().isAssignableFrom(entry.value?.javaClass)) {
                    field.set(this,entry.value)
                } else {
                    // TODO: Maybe need to throw exception ?
                    log("WARNING: Provided property ${entry.key} value with different type, it will be set to null")
                }

            }
        }
    }
    open fun renderImpl() : LatteView? {
        return null
    }


    fun sameView(leftView : LatteView, rightView : LatteView) : Boolean {
        if (leftView.javaClass == rightView.javaClass && leftView.viewType == rightView.viewType) {
            return true;
        }
        return false;
    }

    fun renderTree() {
        var newRenderedViews  = mutableListOf<LatteView>()

        injectProps()

        var renderMe = this.renderImpl()
        if (renderMe != null) {
            renderMe.stylesheet = this.stylesheet
            newRenderedViews.add(renderMe)
        }
        if (this is NativeViewGroup) {
            for (child in this.children) {
                newRenderedViews.add(child)
            }
        }

        for (i in 0..newRenderedViews.size-1) {
            var newView = newRenderedViews.get(i);
            if (i < renderedViews.size) {
                var oldView : LatteView = renderedViews.get(i)
                if (sameView(oldView, newView)) {
                    log("${oldView} is the same as ${newView}")
                    var oldProps = oldView.props
                    oldView.children = newView.children
                    oldView.props = newView.props
                    if (oldView.onPropsUpdated(oldProps)) {
                        oldView.renderTree()
                    }
                    newRenderedViews[i] = oldView
                } else {
                    newView.renderTree()
                }
            } else {
                newView.parentView = this
                newView.stylesheet = this.stylesheet
                newView.renderTree()
            }
        }
        this.renderedViews = newRenderedViews;
    }


    open fun onPropsUpdated(props : Map<String, Any?> ) : Boolean {
        return true;
    }

    open fun render() : String {
        return ""
    }

    fun loadStylesheet(vararg stylesheets : Stylesheet) {
        stylesheets.forEach{ it -> it.apply(this.stylesheet) };
    }

}