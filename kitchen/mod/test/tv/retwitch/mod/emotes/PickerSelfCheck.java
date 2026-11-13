package tv.retwitch.mod.emotes;

import java.util.Arrays;

/** JVM self-check for picker backspace ranges and emote model parsing. Build/run like ads/PlaylistSelfCheck. */
public final class PickerSelfCheck {

    public static void main(String[] args) {
        // Word mode: the last word/emote and the spaces after it; the selection wins.
        assert Arrays.equals(new int[]{6, 12}, PickerInput.deleteRange("hello KEKW  ", -1, -1, true));
        assert Arrays.equals(new int[]{6, 10}, PickerInput.deleteRange("hello KEKW", 10, 10, true));
        assert Arrays.equals(new int[]{0, 5}, PickerInput.deleteRange("hello KEKW", 5, 5, true));
        assert Arrays.equals(new int[]{2, 4}, PickerInput.deleteRange("hello KEKW", 4, 2, true));
        assert PickerInput.deleteRange("", -1, -1, true) == null;
        assert PickerInput.deleteRange("abc", 0, 0, true) == null;
        // Letter mode: one character, a surrogate pair as a whole.
        assert Arrays.equals(new int[]{2, 3}, PickerInput.deleteRange("abc", 3, 3, false));
        assert Arrays.equals(new int[]{1, 3}, PickerInput.deleteRange("a😀", -1, -1, false));
        assert PickerInput.deleteRange("abc", 0, 0, false) == null;

        String[] generic = EmoteModelReflect.parseGeneric("Generic(id=25, token=Kappa, assetType=STATIC, type=GLOBALS)");
        assert generic != null && "25".equals(generic[0]) && "Kappa".equals(generic[1]);
        assert EmoteModelReflect.parseGeneric("Modified(id=1)") == null;
        assert EmoteModelReflect.parseGeneric("Generic(id=, token=x, assetType=A, type=B)") == null;

        assert "Kappa".equals(EmoteSettings.favoriteCode("Kappa 25"));
        assert "KEKW".equals(EmoteSettings.favoriteCode("KEKW"));

        Emote stv = new Emote("a", "https://cdn.7tv.app/emote/X/1x.webp", EmoteSource.SEVENTV, false);
        assert "https://cdn.7tv.app/emote/X/4x.webp".equals(stv.largeImageUrl);
        Emote bttv = new Emote("b", "https://cdn.betterttv.net/emote/Y/2x.webp", EmoteSource.BTTV, false);
        assert "https://cdn.betterttv.net/emote/Y/3x.webp".equals(bttv.largeImageUrl);
        Emote bttvGif = new Emote("c", "https://cdn.betterttv.net/emote/Y/1x", EmoteSource.BTTV, true);
        assert "https://cdn.betterttv.net/emote/Y/3x".equals(bttvGif.largeImageUrl);
        System.out.println("PickerSelfCheck OK");
    }
}
