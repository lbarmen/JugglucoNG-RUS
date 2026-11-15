# JugglucoNG-RUS: R8 rules for build type `rus`, added by rus/rus-init.gradle after upstream's own files.
#
# Drawable XML <drawable class="..."> is inflated by class name (DrawableInflater) and needs the public
# no-arg constructor. Upstream keeps only the class (Common/proguard-rules.my: -keep class ...ComplicationDrawable);
# with AGP's optimized resource shrinking R8 also keeps the constructor itself, but wear `rus` is built with the
# previous shrinker (rus/gradlew-rus.sh), which does not: the watch face crashed with
# NoSuchMethodException: ComplicationDrawable.<init> [] on complication_*_style.xml.
# rus/check-apk.py checks every class named in drawable XML of the wear sources against the wear dex.
-keep class androidx.wear.watchface.complications.rendering.ComplicationDrawable { public <init>(); }
