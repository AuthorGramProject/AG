package org.telegram.ui.Components;

import android.content.Context;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.authorgram.AuthorGramPlayPolicy;

import java.util.HashMap;
import java.util.Map;

import tw.nekomimi.nekogram.NekoConfig;

public class ChatActivityEnterViewAnimatedIconView extends RLottieImageView {
    private State currentState;
    private TransitState animatingState;
    private final int sizeDp;
    private final Map<TransitState, RLottieDrawable> stateMap = new HashMap<>();
    private Boolean drawableIosMode;

    private static boolean iosInput() {
        return AuthorGramPlayPolicy.canUseIosUi()
                && NekoConfig.iOSMessageInputField.Bool();
    }

    public ChatActivityEnterViewAnimatedIconView(Context context) {
        this(context, 32);
    }

    public ChatActivityEnterViewAnimatedIconView(Context context, int sizeDp) {
        super(context);
        this.sizeDp = sizeDp;
    }

    private State normalizeState(State state) {
        if (iosInput() && state == State.MENU) {
            // In iOS-input mode this slot is reserved for voice/video. The chat
            // overflow menu remains in Telegram's ordinary header and must never
            // replace the composer media glyph.
            return State.VOICE;
        }
        return state;
    }

    private RLottieDrawable drawableFor(TransitState state) {
        if (state == null) {
            return null;
        }

        boolean iosMode = iosInput();
        if (drawableIosMode == null || drawableIosMode != iosMode) {
            stateMap.clear();
            drawableIosMode = iosMode;
        }

        RLottieDrawable drawable = stateMap.get(state);
        if (drawable != null) {
            return drawable;
        }

        int res = state.resource;
        if (iosMode) {
            if (state == TransitState.VOICE_TO_VIDEO) {
                res = R.raw.voice_and_video_cg;
            } else if (state == TransitState.VIDEO_TO_VOICE) {
                res = R.raw.voice_and_video_cg_2;
            }
        }

        drawable = new RLottieDrawable(
                res,
                String.valueOf(res),
                AndroidUtilities.dp(sizeDp),
                AndroidUtilities.dp(sizeDp)
        );
        stateMap.put(state, drawable);
        return drawable;
    }

    public void setState(State requestedState, boolean animate) {
        State state = normalizeState(requestedState);

        // A previous animation may have been detached while the composer changed
        // mode. Do not keep a logically correct state with an empty ImageView.
        if (animate && state == currentState && getDrawable() != null) {
            return;
        }

        State fromState = currentState;
        currentState = state;
        TransitState transition = fromState == null ? null : getState(fromState, state);

        if (!animate || fromState == null || transition == null) {
            animatingState = null;
            stopAnimation();

            if (state == State.MENU) {
                setImageResource(R.drawable.ic_ab_other);
            } else {
                RLottieDrawable drawable = drawableFor(getAnyState(state));
                if (drawable == null) {
                    return;
                }
                drawable.stop();
                drawable.setProgress(state == State.VOICE && !iosInput() ? 0.5f : 0.0f, false);
                setAnimation(drawable);
            }
        } else {
            if (transition == animatingState && getDrawable() != null) {
                return;
            }

            animatingState = transition;
            RLottieDrawable drawable = drawableFor(transition);
            if (drawable == null) {
                animatingState = null;
                return;
            }
            drawable.stop();

            if (transition == TransitState.VIDEO_TO_VOICE && !iosInput()) {
                drawable.setCustomEndFrame(30);
                drawable.setProgress(0, false);
            } else if (transition == TransitState.VOICE_TO_VIDEO && !iosInput()) {
                drawable.setCustomEndFrame(60);
                drawable.setProgress(0.5f, false);
            } else {
                drawable.setProgress(0, false);
            }

            drawable.setAutoRepeat(0);
            drawable.setOnAnimationEndListener(() -> animatingState = null);
            setAnimation(drawable);
            AndroidUtilities.runOnUIThread(drawable::start);
        }

        // Explicitly restore the visual slot after every state change. This is
        // what prevents the old black/empty area after clearing input text,
        // cancelling an edit, closing emoji/sticker/GIF, or switching voice/video.
        setVisibility(VISIBLE);
        setAlpha(1.0f);

        switch (state) {
            case VOICE:
                setContentDescription(LocaleController.getString(R.string.AccDescrVoiceMessage));
                break;
            case VIDEO:
                setContentDescription(LocaleController.getString(R.string.AccDescrVideoMessage));
                break;
        }
    }

    public State getCurrentState() {
        return currentState;
    }

    private TransitState getAnyState(State from) {
        for (TransitState transitState : TransitState.values()) {
            if (transitState.firstState == from) {
                return transitState;
            }
        }
        return null;
    }

    private TransitState getState(State from, State to) {
        for (TransitState transitState : TransitState.values()) {
            if (transitState.firstState == from && transitState.secondState == to) {
                return transitState;
            }
        }
        return null;
    }

    private enum TransitState {
        VOICE_TO_VIDEO(State.VOICE, State.VIDEO, R.raw.voice_and_video),
        STICKER_TO_KEYBOARD(State.STICKER, State.KEYBOARD, R.raw.sticker_to_keyboard),
        SMILE_TO_KEYBOARD(State.SMILE, State.KEYBOARD, R.raw.smile_to_keyboard),
        VIDEO_TO_VOICE(State.VIDEO, State.VOICE, R.raw.voice_and_video),
        KEYBOARD_TO_STICKER(State.KEYBOARD, State.STICKER, R.raw.keyboard_to_sticker),
        KEYBOARD_TO_GIF(State.KEYBOARD, State.GIF, R.raw.keyboard_to_gif),
        KEYBOARD_TO_SMILE(State.KEYBOARD, State.SMILE, R.raw.keyboard_to_smile),
        GIF_TO_KEYBOARD(State.GIF, State.KEYBOARD, R.raw.gif_to_keyboard),
        GIF_TO_SMILE(State.GIF, State.SMILE, R.raw.gif_to_smile),
        SMILE_TO_GIF(State.SMILE, State.GIF, R.raw.smile_to_gif),
        SMILE_TO_STICKER(State.SMILE, State.STICKER, R.raw.smile_to_sticker),
        STICKER_TO_SMILE(State.STICKER, State.SMILE, R.raw.sticker_to_smile);

        final State firstState;
        final State secondState;
        final int resource;

        TransitState(State firstState, State secondState, int resource) {
            this.firstState = firstState;
            this.secondState = secondState;
            this.resource = resource;
        }
    }

    public enum State {
        VOICE,
        VIDEO,
        STICKER,
        KEYBOARD,
        SMILE,
        GIF,
        MENU
    }
}
