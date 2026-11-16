# JugglucoNG-RUS: R8 rule of the CareLink trial (part 4p), added to build type `rus` by rus/rus-init.gradle always.
#
# The trial entry point lives in Common/src/rusTrial/java, compiled into the phone `rus` build only with
# -PrusRelease=0 (test builds, emulator only). RusCareLinkStarter finds it by name (Class.forName + install(Context)),
# so its name and that method must stay. In a candidate (-PrusRelease=N, N >= 1) the class does not exist and this
# rule matches nothing; rus/check-apk.py checks that no trial marker string is left in its dex.
-keep class tk.glucodata.rus.carelink.trial.CareLinkTrialEntry {
    public static void install(android.content.Context);
}
