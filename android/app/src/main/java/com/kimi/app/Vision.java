package com.kimi.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.label.ImageLabel;
import com.google.mlkit.vision.label.ImageLabeling;
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 写真をスマホの中で読み取る（Claude に写真を直接渡せない画面のための代わり）。
 * 写っている文字と、写っているものの名前（英語）を返す。
 */
final class Vision {
    static void read(String base64, Actions.Done done) {
        Bitmap bmp;
        try {
            byte[] bytes = Base64.decode(base64, Base64.DEFAULT);
            bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        } catch (Exception e) { bmp = null; }
        if (bmp == null) { done.done("ERROR: 写真を開けませんでした"); return; }
        InputImage image = InputImage.fromBitmap(bmp, 0);
        JSONObject out = new JSONObject();

        TextRecognition.getClient(new JapaneseTextRecognizerOptions.Builder().build())
                .process(image)
                .addOnCompleteListener(t -> {
                    try { out.put("文字", t.isSuccessful() ? t.getResult().getText() : ""); } catch (Exception ignored) { }
                    ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
                            .process(image)
                            .addOnCompleteListener(l -> {
                                try {
                                    JSONArray labels = new JSONArray();
                                    if (l.isSuccessful()) {
                                        for (ImageLabel label : l.getResult()) {
                                            if (label.getConfidence() >= 0.6f && labels.length() < 8) labels.put(label.getText());
                                        }
                                    }
                                    out.put("写っているもの(英語)", labels);
                                } catch (Exception ignored) { }
                                done.done(out.toString());
                            });
                });
    }
}
