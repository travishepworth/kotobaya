package kotobaya.android.styleguide

import kotobaya.android.di.FeatureEntry

/** Release builds carry no styleguide (it lives in the debug source set). */
val styleguideFeature = FeatureEntry(id = "styleguide")
