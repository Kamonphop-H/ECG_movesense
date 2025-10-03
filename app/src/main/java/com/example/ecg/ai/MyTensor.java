package com.example.ecg.ai;

import android.content.Context;

import com.example.ecg.ml.Ecg;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Random;

public class MyTensor {

    private static MyTensor INSTANCE;
    private String info = "Initial info class";

    private MyTensor() {
    }

    public static MyTensor getInstance() {
        if(INSTANCE == null) {
            INSTANCE = new MyTensor();
        }

        return INSTANCE;
    }

    public float[] valid(Context context,float[] input){
        try {

            Ecg model = Ecg.newInstance(context);

            // Creates inputs for reference.
            TensorBuffer inputFeature0 = TensorBuffer.createFixedSize(new int[]{1, 52, 1}, DataType.FLOAT32);

            System.out.println("getFlatSize : "+inputFeature0.getFlatSize());
            System.out.println("getTypeSize : "+inputFeature0.getTypeSize());

//            inputFeature0.loadBuffer(buffer);

            inputFeature0.loadArray(input);

            // Runs model inference and gets result.
            Ecg.Outputs outputs = model.process(inputFeature0);
            TensorBuffer outputFeature0 = outputs.getOutputFeature0AsTensorBuffer();

            float[] data=outputFeature0.getFloatArray();

            System.out.println("outputFeature0 : "+ Arrays.toString(data));

            // Releases model resources if no longer used.
            model.close();

            return data;

        } catch (IOException e) {
            // TODO Handle the exception
        }
        return new float[]{};
    }
}
