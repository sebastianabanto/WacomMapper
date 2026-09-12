package com.example.wacommapper.output;

interface IShizukuInjectionService {
    int getRunningUid();

    String injectMotion(
        int action,
        float x,
        float y,
        float pressure,
        int source,
        int toolType,
        int buttonState,
        int actionButton,
        long downTimeMillis,
        long eventTimeMillis,
        int displayId,
        int injectionMode
    );

    String emergencyStop(float x, float y, long eventTimeMillis);
}
