# R8 rules for the release build (issue #70). Room, WorkManager, Hilt,
# hilt-work, and kotlinx.serialization each ship their own consumer rules
# (bundled in their AAR/JAR under META-INF/proguard or
# META-INF/com.android.tools/r8) that AGP merges in automatically — checked
# every one of those jars/AARs before writing this file, so their generic
# patterns (Worker/ListenableWorker constructors, Hilt EntryPoints,
# @HiltWorker names, RoomDatabase's no-arg constructor, kotlinx.serialization's
# generated $serializer classes and Companion.serializer() lookups) are not
# repeated here. FirebaseMessagingService and every other manifest component
# (MainActivity, DielysApplication) are covered by AGP's default component
# keep rules, and there is no reflection (Class.forName, dynamic ::class.java
# lookups, TypeConverter, or an enum relied on for wire format) anywhere in
# the app's own source.

# So a mapping.txt retrace (uploaded per release, see release-android.yml)
# can turn an obfuscated crash back into a real file and line.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Belt-and-braces for data/remote's sealed wire hierarchies (ChangeEnvelope,
# Mutation, ServerMessage in Wire.kt). Their discriminator-based polymorphic
# serialization should already survive on kotlinx.serialization's own
# consumer rules alone, but this is the one place in the app a missed keep
# fails silently at runtime rather than at compile time — a sync or sign-in
# that quietly parses nothing (see issue #70) — so it gets an explicit rule
# rather than trusting the library alone.
-keep,includedescriptorclasses class za.co.dielys.data.remote.**$$serializer { *; }
-keepclassmembers class za.co.dielys.data.remote.** {
    *** Companion;
}
-keepclasseswithmembers class za.co.dielys.data.remote.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# WorkManager 2.9.1's own rule is `-keep class * extends androidx.work.InputMerger`
# with no members, and in R8 full mode (AGP 8's default) that keeps the class but
# not its no-arg constructor. WorkerWrapper instantiates the merger reflectively
# by name before every job, so without this every sync fails before doWork() —
# "Could not create Input Merger androidx.work.OverwritingInputMerger" — and the
# outbox never drains. Shipped broken in 0.5.6.
-keep class * extends androidx.work.InputMerger {
    public <init>();
}
