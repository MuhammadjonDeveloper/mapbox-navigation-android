package com.mapbox.navigation.ui.internal.utils;

import com.google.auto.value.AutoValue;

import static com.mapbox.navigation.ui.internal.utils.ViewUtils.DEFAULT_BITMAP_ENCODE_COMPRESS_QUALITY;
import static com.mapbox.navigation.ui.internal.utils.ViewUtils.DEFAULT_BITMAP_ENCODE_WIDTH;

@AutoValue
public abstract class BitmapEncodeOptions {
  abstract int width();

  abstract int compressQuality();

  @AutoValue.Builder
  public abstract static class Builder {
    public abstract Builder width(int width);

    public abstract Builder compressQuality(int compressQuality);

    public abstract BitmapEncodeOptions build();
  }

  public static Builder builder() {
    return new AutoValue_BitmapEncodeOptions.Builder()
        .width(DEFAULT_BITMAP_ENCODE_WIDTH)
        .compressQuality(DEFAULT_BITMAP_ENCODE_COMPRESS_QUALITY);
  }
}
