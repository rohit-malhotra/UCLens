/* Copyright 2019 The TensorFlow Authors. All Rights Reserved.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
==============================================================================*/

package com.example.uclens.tracking;

import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Cap;
import android.graphics.Paint.Join;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Pair;
import android.util.TypedValue;
import android.view.View;

import com.example.uclens.R;
import com.example.uclens.env.BorderedText;
import com.example.uclens.env.ImageUtils;
import com.example.uclens.env.Logger;
import com.example.uclens.tflite.Classifier.Recognition;

import java.sql.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;


/** A tracker that handles non-max suppression and matches existing objects to new detections. */
public class MultiBoxTracker {
  private Map<String, IconDetails> titleIconMap;
  private static final float TEXT_SIZE_DIP = 18;
  private static final float MIN_SIZE = 16.0f;
  private static final int[] COLORS = {
    Color.parseColor("#A7B3A1"),
    Color.parseColor("#F7FFF2"),
    Color.parseColor("#A1B3B0"),
    Color.parseColor("#E6FFFB"),
    Color.parseColor("#A7B3A1"),
    Color.parseColor("#F7FFF2"),
    Color.parseColor("#A1B3B0"),
    Color.parseColor("#E6FFFB"),
    Color.parseColor("#A7B3A1"),
    Color.parseColor("#F7FFF2"),
    Color.parseColor("#A1B3B0"),
    Color.parseColor("#E6FFFB"),
    Color.parseColor("#A7B3A1"),
    Color.parseColor("#F7FFF2"),
    Color.parseColor("#A1B3B0"),
    Color.parseColor("#E6FFFB")
  };
  final List<Pair<Float, RectF>> screenRects = new LinkedList<Pair<Float, RectF>>();
  private final Logger logger = new Logger();
  private final Queue<Integer> availableColors = new LinkedList<Integer>();
  private final List<TrackedRecognition> trackedObjects = new LinkedList<TrackedRecognition>();
  private final Paint boxPaint = new Paint();
  private final float textSizePx;
  private final BorderedText borderedText;
  private Matrix frameToCanvasMatrix;
  private int frameWidth;
  private int frameHeight;
  private int sensorOrientation;

  private View.OnClickListener iconClickListener = new View.OnClickListener() {
    @Override
    public void onClick(View view) {
      routeToWeb(view.getContext(),(String) view.getTag());
    }
  };

  private void routeToWeb(Context context, String webUrl) {
    Intent i = new Intent(Intent.ACTION_VIEW);
    i.setData(Uri.parse(webUrl));
    context.startActivity(i);
  }

  public MultiBoxTracker(final Context context) {
    for (final int color : COLORS) {
      availableColors.add(color);
    }

    boxPaint.setColor(Color.RED);
    boxPaint.setStyle(Style.STROKE);
    boxPaint.setStrokeWidth(5.0f);
    boxPaint.setStrokeCap(Cap.ROUND);
    boxPaint.setStrokeJoin(Join.ROUND);
    boxPaint.setStrokeMiter(100);

    textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, context.getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);

    titleIconMap = new HashMap<String, IconDetails>() {{
      put("refrigerator", new IconDetails(
        context.getResources().getDrawable(R.drawable.appliance_repair),
        "Not cooling?",
        "https://www.urbancompany.com/delhi-ncr-refrigerator-repair"
      ));
      put("tv", new IconDetails(
        context.getResources().getDrawable(R.drawable.appliance_repair),
        "No Netflix?",
        "https://www.urbancompany.com/delhi-ncr-tv-repair"
      ));
      put("microwave", new IconDetails(
        context.getResources().getDrawable(R.drawable.appliance_repair),
        "Cold food?",
        "https://www.urbancompany.com/delhi-ncr-microwave-repair"
      ));
      put("oven", new IconDetails(
        context.getResources().getDrawable(R.drawable.appliance_repair),
        "Brownie points?",
        "https://www.urbancompany.com/delhi-ncr-microwave-repair"
      ));
      put("dining table", new IconDetails(
        context.getResources().getDrawable(R.drawable.carpenter),
        "Needs polish?",
        "https://www.urbancompany.com/delhi-ncr-carpenters"
      ));
      put("chair", new IconDetails(
        context.getResources().getDrawable(R.drawable.carpenter),
        "Need carpenter?",
        "https://www.urbancompany.com/delhi-ncr-carpenters"
      ));
      put("sink", new IconDetails(
        context.getResources().getDrawable(R.drawable.cleaning),
        "Needs cleaning?",
        "https://www.flaticon.com/packs/electronics-106?word=electronics"
      ));
    }};

    for (IconDetails iconObj : titleIconMap.values()){

    }
  }

  public synchronized void setFrameConfiguration(
      final int width, final int height, final int sensorOrientation) {
    frameWidth = width;
    frameHeight = height;
    this.sensorOrientation = sensorOrientation;
  }

  public synchronized void drawDebug(final Canvas canvas) {
    final Paint textPaint = new Paint();
    textPaint.setColor(Color.WHITE);
    textPaint.setTextSize(60.0f);

    final Paint boxPaint = new Paint();
    boxPaint.setColor(Color.RED);
    boxPaint.setAlpha(200);
    boxPaint.setStyle(Style.STROKE);

    for (final Pair<Float, RectF> detection : screenRects) {
      final RectF rect = detection.second;
      canvas.drawRect(rect, boxPaint);
      canvas.drawText("" + detection.first, rect.left, rect.top, textPaint);
      borderedText.drawText(canvas, rect.centerX(), rect.centerY(), "" + detection.first);
    }
  }

  public synchronized void trackResults(final List<Recognition> results, final long timestamp) {
    logger.i("Processing %d results from %d", results.size(), timestamp);
    processResults(results);
  }

  private Matrix getFrameToCanvasMatrix() {
    return frameToCanvasMatrix;
  }

  public synchronized void draw(final Canvas canvas) {
    final boolean rotated = sensorOrientation % 180 == 90;
    final float multiplier =
        Math.min(
            canvas.getHeight() / (float) (rotated ? frameWidth : frameHeight),
            canvas.getWidth() / (float) (rotated ? frameHeight : frameWidth));
    frameToCanvasMatrix =
        ImageUtils.getTransformationMatrix(
            frameWidth,
            frameHeight,
            (int) (multiplier * (rotated ? frameHeight : frameWidth)),
            (int) (multiplier * (rotated ? frameWidth : frameHeight)),
            sensorOrientation,
            false);
    for (final TrackedRecognition recognition : trackedObjects) {
      final RectF trackedPos = new RectF(recognition.location);

      getFrameToCanvasMatrix().mapRect(trackedPos);
      boxPaint.setColor(recognition.color);

      float cornerSize = Math.min(trackedPos.width(), trackedPos.height()) / 8.0f;
      canvas.drawRoundRect(trackedPos, cornerSize, cornerSize, boxPaint);

      int centerX = (int) (trackedPos.left + trackedPos.right) / 2;
      int centerY = (int) (trackedPos.top + trackedPos.bottom) / 2;
      int width = 200;
      int height = 200;

      IconDetails iconObject = titleIconMap.get(recognition.title);
      int iconLeft = centerX - width/2;
      int iconTop = centerY - height/2;
      int iconRight = centerX + width/2;
      int iconBottom = centerY + height/2;
      iconObject.icon.setBounds(iconLeft, iconTop, iconRight, iconBottom);
      iconObject.icon.draw(canvas);
      borderedText.drawText(canvas, iconLeft, iconBottom, iconObject.text, boxPaint);


      final String labelString = recognition.title;

//                  borderedText.drawText(canvas, trackedPos.left + cornerSize, trackedPos.top,
//       labelString);
      borderedText.drawText(
          canvas, trackedPos.left + cornerSize, trackedPos.top, labelString, boxPaint);
    }
  }

  private void processResults(final List<Recognition> results) {
    final List<Pair<Float, Recognition>> rectsToTrack = new LinkedList<Pair<Float, Recognition>>();

    screenRects.clear();
    final Matrix rgbFrameToScreen = new Matrix(getFrameToCanvasMatrix());

    for (final Recognition result : results) {
      if (result.getLocation() == null) {
        continue;
      }
      final RectF detectionFrameRect = new RectF(result.getLocation());

      final RectF detectionScreenRect = new RectF();
      rgbFrameToScreen.mapRect(detectionScreenRect, detectionFrameRect);

      logger.v(
          "Result! Frame: " + result.getLocation() + " mapped to screen:" + detectionScreenRect);

      screenRects.add(new Pair<Float, RectF>(result.getConfidence(), detectionScreenRect));

      if (detectionFrameRect.width() < MIN_SIZE || detectionFrameRect.height() < MIN_SIZE) {
        logger.w("Degenerate rectangle! " + detectionFrameRect);
        continue;
      }

      rectsToTrack.add(new Pair<Float, Recognition>(result.getConfidence(), result));
    }

    trackedObjects.clear();
    if (rectsToTrack.isEmpty()) {
      logger.v("Nothing to track, aborting.");
      return;
    }

    for (final Pair<Float, Recognition> potential : rectsToTrack) {
      final TrackedRecognition trackedRecognition = new TrackedRecognition();
      trackedRecognition.detectionConfidence = potential.first;
      trackedRecognition.location = new RectF(potential.second.getLocation());
      trackedRecognition.title = potential.second.getTitle();
      trackedRecognition.color = COLORS[trackedObjects.size()];
      if (!Arrays.asList("refrigerator", "tv", "microwave", "oven",
        "dining table", "chair", "sink").contains(trackedRecognition.title)) {
        continue;
      }

      trackedObjects.add(trackedRecognition);

      if (trackedObjects.size() >= COLORS.length) {
        break;
      }
    }
  }

  private static class TrackedRecognition {
    RectF location;
    float detectionConfidence;
    int color;
    String title;
  }
}

class IconDetails {
  Drawable icon;
  String text;
  String webUrl;

  public IconDetails(Drawable icon, String text, String webUrl) {
    this.icon = icon;
    this.text = text;
    this.webUrl = webUrl;
  }
}