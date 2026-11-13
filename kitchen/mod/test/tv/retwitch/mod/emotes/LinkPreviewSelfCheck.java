package tv.retwitch.mod.emotes;

/** JVM self-check for link -> image URL mapping. Build/run like ads/PlaylistSelfCheck. */
public final class LinkPreviewSelfCheck {

    public static void main(String[] args) {
        assert "https://i.imgur.com/AbC123.jpg".equals(LinkPreview.imageUrl("https://imgur.com/AbC123"));
        assert "https://cdn.7tv.app/emote/01ABCD/4x.webp".equals(LinkPreview.imageUrl("https://7tv.app/emotes/01ABCD"));
        assert "https://i.giphy.com/xT9IgG50Fb7Mi0prBC.webp".equals(
                LinkPreview.imageUrl("https://media3.giphy.com/media/xT9IgG50Fb7Mi0prBC/giphy.gif?cid=1&rid=giphy.gif"));
        assert "https://i.giphy.com/l0MYt5jPR6QX5pnqM.webp".equals(
                LinkPreview.imageUrl("https://giphy.com/gifs/funny-cat-l0MYt5jPR6QX5pnqM"));
        assert "https://i.giphy.com/abc.webp".equals(LinkPreview.imageUrl("https://i.giphy.com/abc.webp"));
        assert "https://x.com/pic.PNG".equals(LinkPreview.imageUrl("https://x.com/pic.PNG),"));
        assert "https://x.com/a.webp?w=1".equals(LinkPreview.imageUrl("https://x.com/a.webp?w=1"));
        assert "https://kappa.lol/Ab12".equals(LinkPreview.imageUrl("https://kappa.lol/Ab12"));
        assert LinkPreview.imageUrl("https://youtube.com/watch?v=1") == null;
        assert LinkPreview.imageUrl("imgur.com/AbC123") == null;
        assert LinkPreview.imageUrl("https://imgur.com/a/AbC123") == null;
        String gifyu = "https://s15.gifyu.com/images/bWAhb.gif";
        assert LinkPreview.useProxy(gifyu, LinkPreview.imageUrl(gifyu));
        assert !LinkPreview.useProxy("https://gifyu.com/a.gif", "https://gifyu.com/a.gif");
        System.out.println("LinkPreviewSelfCheck OK");
    }
}
