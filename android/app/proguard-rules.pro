# Glance widget eylemleri (onay kutusu, silme) sınıf adından yansıma ile örneklenir;
# R8 boş yapıcıları "kullanılmıyor" sanıp silmesin.
-keep class * implements androidx.glance.appwidget.action.ActionCallback {
    <init>();
}
