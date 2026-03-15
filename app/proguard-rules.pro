# PDFBox-Android rules
-keep class com.tom_roush.pdfbox.** { *; }
-dontwarn com.tom_roush.pdfbox.**
-keep class org.apache.fontbox.** { *; }
-dontwarn org.apache.fontbox.**

# AndroidX PDF Viewer rules
-keep class androidx.pdf.** { *; }
-dontwarn androidx.pdf.**

# Standard Material & Compose rules
-keep class androidx.compose.material3.** { *; }
-keep class com.google.android.material.** { *; }

# Play Services Ads rules
-keep class com.google.android.gms.ads.** { *; }
-dontwarn com.google.android.gms.ads.**
