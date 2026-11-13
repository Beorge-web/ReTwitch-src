// Runs before Twitch's bundle (RnScript). Metro modules are found by their export names when they
// load: ids change with every build and with RN hot updates, these names don't.
(function (global) {
    'use strict';
    var config = global.__retwitchRn;
    var React, RN, core, icons, host, numbers, define, openChannel, contentReady = false;
    var fav = !!config.favorites;
    var targets = {FeedStreamItem: config.compactLive, FeedAdItem: config.compactLive, RecapsShelfHeader: config.hideFollowedShelf,
        FeedShelfContainer: config.hideClipsShelf, FollowingTab: !!config.friends || fav, FollowingCategories: !!config.friends,
        useFollowingTabData: fav, FollowingLiveHeader: fav, TrackedFollowingChannelRow: fav, FollowingChannelMenu: fav,
        ChannelProfileOverflowMenu: fav,
        StoriesEmptyState: config.hideStoriesEmpty, useStoriesShelf: config.hideStoriesEmpty || config.hideStories,
        FollowingChannelRow: config.previewSize > 0, CustomTabIndicator: config.tabIndicator,
        StoriesShelf: config.hideStories, CollapsedStoriesIcons: config.hideStories, FeedTabsRow: config.hideTabs,
        FreeScrollCellRenderer: config.compactLive, ChannelProfileRecapsShelf: config.hideProfileRecaps,
        ChannelProfileClipShelf: config.hideProfileClips,
        ChannelProfileHomeShelf: config.hideProfileCategories || config.hideProfileStreamers,
        ChannelProfileTrackedShelf: config.hideProfileRecaps || config.hideProfileClips};
    var FRIENDS_SHOWN = 5;
    // Tabs' content container: paddingEnd is space-0200 (paddingStart, where the first tab starts, is space-0250).
    var TABS_PADDING_END = 16;
    // The stories shelf shows the hidden empty card: its gesture should refresh, not reveal (useStoriesShelf).
    var storiesEmpty = !!config.hideStories, shelfListeners = [];

    function setStoriesEmpty(value) {
        storiesEmpty = value;
        shelfListeners.slice().forEach(function (listener) { listener(value); });
    }
    var RTE = 'https://ext.rte.net.ru:8443';
    var GQL_CLIENT = 'kd1unb4b3q4t58fwlpcbzcbnm76a8fp';

    function viewers(content) {
        var costream = content.costreamDetails;
        var count = costream && costream.role === 'ORGANIZER' && costream.totalViewersCount != null
            ? costream.totalViewersCount
            : content.collaborationViewersCount != null ? content.collaborationViewersCount : content.viewersCount || 0;
        try {
            numbers = numbers || new Intl.NumberFormat(config.locale, {notation: 'compact', maximumFractionDigits: 1});
            return numbers.format(count);
        } catch (e) {
            return String(count);
        }
    }

    var styles = {
        row: {flexDirection: 'row', paddingHorizontal: 16, paddingVertical: 8},
        thumbnail: {width: 128, height: 72, borderRadius: 4, backgroundColor: '#26262c', overflow: 'hidden'},
        image: {width: 128, height: 72},
        count: {position: 'absolute', left: 4, bottom: 4, flexDirection: 'row', alignItems: 'center',
            paddingHorizontal: 4, borderRadius: 3, backgroundColor: 'rgba(0,0,0,0.6)'},
        dot: {width: 8, height: 8, marginRight: 4, borderRadius: 4, backgroundColor: '#eb0400'},
        info: {flex: 1, marginLeft: 12},
        header: {flexDirection: 'row', alignItems: 'center'},
        avatar: {width: 18, height: 18, marginRight: 6, borderRadius: 9},
        name: {flexShrink: 1},
        verified: {marginLeft: 4},
        tags: {flexDirection: 'row', marginTop: 4, overflow: 'hidden'},
        tag: {marginRight: 4, paddingHorizontal: 6, borderRadius: 9, backgroundColor: '#35353b'},
        uptime: {position: 'absolute', top: 4, right: 4, borderRadius: 3, backgroundColor: 'rgba(0,0,0,0.6)'},
        uptimeText: {color: '#ffffff', fontSize: 11, textAlign: 'center'}
    };

    // The splash waits for this. The stock card sends it from its own player logic, which isn't mounted here.
    function thumbnailDone() {
        if (contentReady || !host) return;
        contentReady = true;
        host.hostFeedContentReady.notifyFirstFeedThumbnailLoaded();
    }

    // The feed's FlatList has no getItemLayout, so VirtualizedList sizes the spacer above its window from absolute
    // cell offsets measured in some earlier layout. Made for full-screen cards (initialNumToRender 1, 2 cells per
    // 100 ms); with short rows the window moves every batch, the stale offsets shift everything by a row or so, and
    // that moves the window again: the Live tab jumped on its own. Offsets here are sums of measured lengths, so
    // they never disagree with each other.
    var cellRenderer = null, cellLengths = {}, layoutVersion = 0, listHeader = 0, rowLength = 98, flatListPatched = false,
        cellCount = 0;

    // Pixel snapping gives the same row 97.9 or 98.3 dp depending on where it lands: such changes aren't news.
    function differs(a, b) {
        return a === undefined || Math.abs(a - b) >= 1;
    }

    function noteCell(key, index, layout) {
        if (index === 0 && differs(listHeader, layout.y)) { listHeader = layout.y; layoutVersion++; }
        if (!differs(cellLengths[key], layout.height)) return;
        // Keys carry the feed request id, every refresh brings new ones: forget them all now and then.
        if (++cellCount > 3000) { cellLengths = {}; cellCount = 1; }
        cellLengths[key] = layout.height;
        layoutVersion++;
    }

    function guessLength(item) {
        var node = item && item.node;
        if (node && node.content && node.content.broadcaster) return rowLength;
        if (node && (node.__typename === 'FeedAd' || (config.hideClipsShelf && node.headerAccessory === 'CLIP_ICON'))) return 0;
        return rowLength;
    }

    function feedItemLayout(list) {
        var data = null, version = -1, offsets = [];
        return function (items, index) {
            if (items !== data || version !== layoutVersion) {
                var keyOf = list.props.keyExtractor, sum = listHeader;
                offsets = new Array(items.length + 1);
                for (var i = 0; i < items.length; i++) {
                    offsets[i] = sum;
                    var key = keyOf ? keyOf(items[i], i) : String(i);
                    sum += key in cellLengths ? cellLengths[key] : guessLength(items[i]);
                }
                offsets[items.length] = sum;
                data = items;
                version = layoutVersion;
            }
            return {length: offsets[index + 1] - offsets[index], offset: offsets[index], index: index};
        };
    }

    function patchFlatList(FlatList) {
        var render = FlatList.prototype.render;
        FlatList.prototype.render = function () {
            var element = render.apply(this, arguments);
            if (!element || !cellRenderer || this.props.CellRendererComponent !== cellRenderer) return element;
            this.__retwitchLayout = this.__retwitchLayout || feedItemLayout(this);
            return React.cloneElement(element, {getItemLayout: this.__retwitchLayout});
        };
    }


    // Stream uptime on previews (feedback #52). Every live preview is an RN Image with a previews-ttv URL that names
    // the channel (live_user_<login>-WxH.jpg), so wrapping react-native's Image covers all RN screens at once. Start
    // times come in batches of up to 100 logins (anonymous GQL), kept 10 minutes; one shared minute tick redraws.
    var uptimes = {}, uptimeQueue = {}, uptimeTimer = null, uptimeListeners = [], uptimeTick = null, imagePatched = false;
    var UPTIME_FRESH = 600000;

    function uptimeLogin(source) {
        var first = Array.isArray(source) ? source[0] : source;
        var uri = first && first.uri;
        var m = typeof uri === 'string' && /previews-ttv\/live_user_([A-Za-z0-9_]+)-\d+x\d+\./.exec(uri);
        return m ? m[1].toLowerCase() : null;
    }

    function requestUptime(login) {
        var known = uptimes[login];
        if (known && Date.now() - known.at < UPTIME_FRESH || uptimeQueue[login]) return;
        uptimeQueue[login] = true;
        if (!uptimeTimer) uptimeTimer = setTimeout(fetchUptimes, 300);
    }

    function fetchUptimes() {
        uptimeTimer = null;
        var logins = Object.keys(uptimeQueue).slice(0, 100);
        var now = Date.now();
        logins.forEach(function (login) {
            delete uptimeQueue[login];
            uptimes[login] = {at: now, start: uptimes[login] ? uptimes[login].start : null};
        });
        if (Object.keys(uptimeQueue).length) uptimeTimer = setTimeout(fetchUptimes, 300);
        fetch('https://gql.twitch.tv/gql', {method: 'POST', headers: {'Client-ID': GQL_CLIENT, 'Content-Type': 'application/json'},
            body: JSON.stringify({query: 'query($l:[String!]){users(logins:$l){login stream{createdAt}}}', variables: {l: logins}})})
            .then(function (r) { return r.json(); })
            .then(function (j) {
                (j && j.data && j.data.users || []).forEach(function (u) {
                    if (!u || !u.login) return;
                    var start = u.stream && Date.parse(u.stream.createdAt);
                    uptimes[u.login.toLowerCase()] = {at: Date.now(), start: start || null};
                });
                uptimeListeners.slice().forEach(function (listener) { listener(); });
            })
            .catch(function () {
                // Again in a minute, not on every render.
                logins.forEach(function (login) { uptimes[login].at = Date.now() - UPTIME_FRESH + 60000; });
            });
    }

    function formatUptime(ms) {
        var minutes = Math.max(0, Math.floor(ms / 60000));
        var m = minutes % 60;
        return Math.floor(minutes / 60) + ':' + (m < 10 ? '0' : '') + m;
    }

    function Uptime(props) {
        var state = React.useState(0), redraw = state[1];
        React.useEffect(function () {
            requestUptime(props.login);
            var listener = function () { redraw(function (n) { return n + 1; }); };
            uptimeListeners.push(listener);
            if (!uptimeTick) uptimeTick = setInterval(function () {
                uptimeListeners.slice().forEach(function (l) { l(); });
            }, 60000);
            return function () {
                uptimeListeners.splice(uptimeListeners.indexOf(listener), 1);
                if (!uptimeListeners.length && uptimeTick) { clearInterval(uptimeTick); uptimeTick = null; }
            };
        }, [props.login]);
        var known = uptimes[props.login];
        if (!known || !known.start) return null;
        var text = formatUptime(Date.now() - known.start);
        // A width of its own: measured to fit, the text still got cut to "2:..." on the Pixel.
        return React.createElement(RN.View, {pointerEvents: 'none', style: [styles.uptime, {width: text.length * 7 + 10}]},
            React.createElement(RN.Text, {style: styles.uptimeText, numberOfLines: 1}, text));
    }

    // Image keeps its own layout: the box takes its style, the picture fills the box, the badge sits in its corner.
    var IMAGE_ONLY = {resizeMode: 1, tintColor: 1, overlayColor: 1, objectFit: 1};

    function patchImage(module) {
        var Original = module.default;
        var Wrapped = React.forwardRef(function (props, ref) {
            var login = uptimeLogin(props.source);
            if (!login) return React.createElement(Original, Object.assign({}, props, {ref: ref}));
            var flat = RN.StyleSheet.flatten(props.style) || {};
            var box = {};
            Object.keys(flat).forEach(function (k) { if (!IMAGE_ONLY[k]) box[k] = flat[k]; });
            var fill = Object.assign({}, flat, {position: 'absolute', top: 0, left: 0, right: 0, bottom: 0, width: '100%',
                height: '100%', margin: 0, marginTop: 0, marginBottom: 0, marginLeft: 0, marginRight: 0, marginStart: 0,
                marginEnd: 0, marginHorizontal: 0, marginVertical: 0, transform: undefined});
            return React.createElement(RN.View, {style: box, pointerEvents: 'box-none'},
                React.createElement(Original, Object.assign({}, props, {ref: ref, style: fill})),
                React.createElement(Uptime, {login: login}));
        });
        Object.keys(Original).forEach(function (k) { if (!(k in Wrapped)) Wrapped[k] = Original[k]; });
        Wrapped.displayName = 'Image';
        module.default = Wrapped;
    }

    function Row(props) {
        var content = props.node.content;
        var broadcaster = content && content.broadcaster;
        if (broadcaster == null) return null;
        var h = React.createElement;
        var Label = core ? core.CoreText : RN.Text;
        var settings = broadcaster.broadcastSettings;
        function open() {
            if (props.onItemClick) props.onItemClick('tap_to_enter');
            props.onOpenChannel(broadcaster.login, undefined, broadcaster.id, content.isDualFormat === true,
                content.height || 0, content.width || 0, content.isEncrypted === true);
        }
        // CoreText takes bold/muted/type and no style; the style is for the RN.Text fallback.
        function text(props, value) {
            return h(Label, Object.assign({numberOfLines: 1, style: {color: '#efeff1'}}, props), value);
        }
        return h(RN.Pressable, {style: styles.row, onPress: open, accessibilityRole: 'button',
                android_ripple: {color: 'rgba(255,255,255,0.1)'}},
            h(RN.View, {style: styles.thumbnail},
                h(RN.Image, {source: {uri: content.previewImageURL}, style: styles.image, resizeMode: 'cover',
                    onLoad: thumbnailDone, onError: thumbnailDone}),
                h(RN.View, {style: styles.count},
                    h(RN.View, {style: styles.dot}),
                    text({}, viewers(content)))),
            h(RN.View, {style: styles.info},
                h(RN.View, {style: styles.header},
                    broadcaster.profileImageURL
                        ? h(RN.Image, {source: {uri: broadcaster.profileImageURL}, style: styles.avatar}) : null,
                    h(RN.View, {style: styles.name}, text({bold: true}, broadcaster.displayName || broadcaster.login)),
                    icons && broadcaster.roles && broadcaster.roles.isPartner
                        ? h(RN.View, {style: styles.verified}, h(icons.IconVerified, {size: 16, fillColor: '#bf94ff'})) : null),
                settings && settings.title ? text({}, settings.title) : null,
                content.game ? text({muted: true}, content.game.displayName) : null,
                content.freeformTags && content.freeformTags.length
                    ? h(RN.View, {style: styles.tags}, content.freeformTags.slice(0, 3).map(function (tag) {
                        return h(RN.View, {key: tag.id || tag.name, style: styles.tag}, text({type: 'caption', muted: true}, tag.name));
                    })) : null));
    }

    var bigStyles = {
        card: {paddingHorizontal: 16, paddingVertical: 8},
        preview: {width: '100%', aspectRatio: 16 / 9, borderRadius: 6, backgroundColor: '#26262c', overflow: 'hidden'},
        image: {width: '100%', height: '100%'},
        info: {flexDirection: 'row', marginTop: 8},
        avatar: {width: 36, height: 36, marginRight: 10, borderRadius: 18, backgroundColor: '#35353b'},
        text: {flex: 1},
        // previewSize 1: the stock row's layout with a preview about twice its size.
        mediumRow: {flexDirection: 'row', paddingHorizontal: 16, paddingVertical: 8},
        mediumPreview: {width: '45%', aspectRatio: 16 / 9, borderRadius: 6, backgroundColor: '#26262c', overflow: 'hidden'},
        mediumInfo: {flex: 1, marginLeft: 12}
    };

    // The query asks 440x248; previews-ttv draws any size, and a full-width one needs more.
    function bigPreview(url) {
        return typeof url === 'string' ? url.replace(/-440x248\./, '-960x540.') : url;
    }

    function blurred(stream) {
        var reason = stream.previewThumbnailProperties && stream.previewThumbnailProperties.blurReason;
        return !!reason && reason !== 'NONE' && reason !== 'BLUR_NOT_REQUIRED';
    }

    // Following's live row (FollowingChannelRow, under TrackedFollowingChannelRow) with a full-width preview: the
    // stream fields of MwebFollowingTabSections' followedLiveUsers. The launch cover waits for the row's
    // onThumbnailSettled / onAvatarSettled (gatesAppLoad), so both are called on load and on error.
    function settled(callback) {
        return function () { if (callback) callback(); };
    }

    function BigCard(props) {
        var stream = props.stream;
        var broadcaster = stream && stream.broadcaster;
        var avatarSettled = settled(props.onAvatarSettled);
        React.useEffect(function () {
            if (!broadcaster || !broadcaster.profileImageURL) avatarSettled();
        }, []);
        if (broadcaster == null) return null;
        var h = React.createElement;
        var Label = core ? core.CoreText : RN.Text;
        var settings = broadcaster.broadcastSettings;
        var medium = config.previewSize === 1;
        function open() {
            props.onOpenChannel(broadcaster.login, undefined, broadcaster.id, stream.isDualFormat === true,
                stream.height || 0, stream.width || 0, stream.isEncrypted === true);
        }
        function text(props, value) {
            return h(Label, Object.assign({numberOfLines: 1, style: {color: '#efeff1'}}, props), value);
        }
        var preview = h(RN.View, {style: medium ? bigStyles.mediumPreview : bigStyles.preview},
            h(RN.Image, {source: {uri: bigPreview(stream.previewImageURL)}, style: bigStyles.image, resizeMode: 'cover',
                blurRadius: blurred(stream) ? 25 : 0,
                onLoad: settled(props.onThumbnailSettled), onError: settled(props.onThumbnailSettled)}),
            h(RN.View, {style: styles.count},
                h(RN.View, {style: styles.dot}),
                text({}, viewers(stream))));
        if (medium) {
            return h(RN.Pressable, {style: bigStyles.mediumRow, onPress: open, accessibilityRole: 'button',
                    android_ripple: {color: 'rgba(255,255,255,0.1)'}, testID: props.testID},
                preview,
                h(RN.View, {style: bigStyles.mediumInfo},
                    h(RN.View, {style: styles.header},
                        broadcaster.profileImageURL ? h(RN.Image, {source: {uri: broadcaster.profileImageURL}, style: styles.avatar,
                            onLoad: avatarSettled, onError: avatarSettled}) : null,
                        h(RN.View, {style: styles.name}, text({bold: true}, broadcaster.displayName || broadcaster.login)),
                        icons && broadcaster.roles && broadcaster.roles.isPartner
                            ? h(RN.View, {style: styles.verified}, h(icons.IconVerified, {size: 16, fillColor: '#bf94ff'})) : null),
                    settings && settings.title ? h(Label, {numberOfLines: 2, style: {color: '#efeff1'}}, settings.title) : null,
                    stream.game ? text({muted: true}, stream.game.displayName || stream.game.name) : null));
        }
        return h(RN.Pressable, {style: bigStyles.card, onPress: open, accessibilityRole: 'button',
                android_ripple: {color: 'rgba(255,255,255,0.1)'}, testID: props.testID},
            preview,
            h(RN.View, {style: bigStyles.info},
                broadcaster.profileImageURL
                    ? h(RN.Image, {source: {uri: broadcaster.profileImageURL}, style: bigStyles.avatar,
                        onLoad: avatarSettled, onError: avatarSettled})
                    : h(RN.View, {style: bigStyles.avatar}),
                h(RN.View, {style: bigStyles.text},
                    h(RN.View, {style: styles.header},
                        h(RN.View, {style: styles.name}, text({bold: true}, broadcaster.displayName || broadcaster.login)),
                        icons && broadcaster.roles && broadcaster.roles.isPartner
                            ? h(RN.View, {style: styles.verified}, h(icons.IconVerified, {size: 16, fillColor: '#bf94ff'})) : null),
                    settings && settings.title ? text({}, settings.title) : null,
                    stream.game ? text({muted: true}, stream.game.displayName || stream.game.name) : null)));
    }

    // RTE friends: who is online and where (as the settings list and Frosty show them), under Live in Following.
    function Friends() {
        var h = React.createElement;
        var state = React.useState(null), list = state[0], setList = state[1];
        var expanded = React.useState(false), all = expanded[0], setAll = expanded[1];
        React.useEffect(function () {
            var live = true;
            function load() {
                fetch(RTE + '/api/ext/friends/' + encodeURIComponent(config.friends))
                    .then(function (r) { return r.ok ? r.json() : []; })
                    .then(function (friends) {
                        if (live) setList((friends || []).filter(function (f) {
                            return f.is_online && f.channels && f.channels.length;
                        }));
                    })
                    .catch(function () {});
            }
            load();
            var timer = setInterval(load, 60000);
            return function () { live = false; clearInterval(timer); };
        }, []);
        if (!list || !list.length) return null;
        var Label = core ? core.CoreText : RN.Text;
        function text(props, value) {
            return h(Label, Object.assign({numberOfLines: 1, style: {color: '#efeff1'}}, props), value);
        }
        // The host opens a channel by login and id (FollowingTab's onOpenChannel); the id comes from GQL.
        function open(login) {
            fetch('https://gql.twitch.tv/gql', {method: 'POST', headers: {'Client-ID': GQL_CLIENT,
                    'Content-Type': 'application/json'},
                body: JSON.stringify({query: 'query($l:String!){user(login:$l){id}}', variables: {l: login}})})
                .then(function (r) { return r.json(); })
                .then(function (j) {
                    var id = j && j.data && j.data.user ? j.data.user.id : undefined;
                    if (openChannel) openChannel(login, undefined, id, false, 0, 0, false);
                    else RN.Linking.openURL('twitch://stream/' + login);
                })
                .catch(function () {});
        }
        var shown = all ? list : list.slice(0, FRIENDS_SHOWN);
        return h(RN.View, {style: friendStyles.block},
            h(RN.View, {style: friendStyles.header}, text({bold: true}, config.text.friends + ' · ' + list.length)),
            shown.map(function (f) {
                var ch = f.channels[0];
                var where = config.text.watching + ' ' + (ch.channel_display_name || ch.channel_login)
                    + (ch.stream_category ? ' · ' + ch.stream_category : '');
                return h(RN.Pressable, {key: f.twitch_id, style: friendStyles.row, onPress: function () { open(ch.channel_login); },
                        android_ripple: {color: 'rgba(255,255,255,0.1)'}, accessibilityRole: 'button'},
                    f.profile_image_url ? h(RN.Image, {source: {uri: f.profile_image_url}, style: friendStyles.avatar})
                        : h(RN.View, {style: friendStyles.avatar}),
                    h(RN.View, {style: friendStyles.info},
                        text({bold: true}, f.display_name || f.login),
                        text({muted: true}, where)));
            }),
            list.length > FRIENDS_SHOWN ? h(RN.Pressable, {style: friendStyles.more, onPress: function () { setAll(!all); },
                    accessibilityRole: 'button'},
                text({bold: true}, all ? config.text.less : config.text.more + ' (' + list.length + ')')) : null);
    }

    // The Following list content has its own 16 dp sides already.
    var friendStyles = {
        block: {paddingVertical: 8},
        header: {paddingVertical: 6},
        row: {flexDirection: 'row', alignItems: 'center', paddingVertical: 6},
        avatar: {width: 36, height: 36, borderRadius: 18, backgroundColor: '#35353b'},
        info: {flex: 1, marginLeft: 12},
        more: {marginTop: 4, paddingVertical: 8, alignItems: 'center', borderRadius: 6,
            backgroundColor: '#26262c'}
    };

    // Favorite channels: [{id, login, name}] in the order they were added. The list lives in native prefs: config
    // brings it in, every change goes out as a fetch the native request hook keeps (FavoriteChannels.fromRn).
    var favorites = config.favorites || [], favListeners = [];
    var FAVORITES_URL = 'https://retwitch.invalid/favorites?list=';
    // From the Following data (useFollowingTabData, rendered before the header and rows): ids of live favorites
    // shown as rows, the last of them, and how many other live rows follow.
    var liveFav = {ids: {}, last: null, rest: 0, edges: null};
    var LiveHeader = null, liveHeaderProps = null, openMenu = null, stars = {}, sheets = null, overflowItem = null;
    var favUsers = {}, favFetched = 0;

    function isFavorite(id) {
        return favorites.some(function (f) { return f.id === id; });
    }

    function setFavorite(target, on) {
        var id = String(target.channelId);
        favorites = favorites.filter(function (f) { return f.id !== id; });
        if (on) favorites = favorites.concat([{id: id, login: target.login, name: target.displayName || target.login}]);
        favListeners.slice().forEach(function (listener) { listener(favorites); });
        fetch(FAVORITES_URL + encodeURIComponent(JSON.stringify(favorites))).catch(function () {});
    }

    function useFavorites() {
        var state = React.useState(favorites), setList = state[1];
        React.useEffect(function () {
            favListeners.push(setList);
            setList(favorites);
            return function () { favListeners.splice(favListeners.indexOf(setList), 1); };
        }, []);
        return favorites;
    }

    function edgeId(edge) {
        var b = edge && edge.node && edge.node.stream && edge.node.stream.broadcaster;
        return b ? String(b.id) : null;
    }

    // Live favorites first, each group in Twitch's order (its sort menu still works on them).
    function favoritesFirst(edges, list) {
        var wanted = {}, front = [], rest = [], ids = {};
        list.forEach(function (f) { wanted[f.id] = true; });
        (edges || []).forEach(function (edge) {
            var id = edgeId(edge);
            if (id && wanted[id]) { front.push(edge); ids[id] = true; } else rest.push(edge);
        });
        return {ids: ids, last: front.length ? edgeId(front[front.length - 1]) : null, rest: rest.length,
            edges: front.length && edges ? front.concat(rest) : edges};
    }

    function FavoritesHeader() {
        var list = useFavorites();
        return React.createElement(RN.View, {style: favStyles.header},
            React.createElement(RN.Text, {style: favStyles.star}, '★'),
            label({type: 'heading', family: 'body', bold: true}, config.text.favorites + ' · ' + list.length));
    }

    function label(props, value) {
        var Label = core ? core.CoreText : RN.Text;
        return React.createElement(Label, Object.assign({numberOfLines: 1, style: {color: '#efeff1'}}, props), value);
    }

    // Favorites without a live row: offline, or live but not followed. Avatars from GQL (ids, anonymous).
    function FavoritesStrip() {
        var list = useFavorites();
        var loaded = React.useState(0), setLoaded = loaded[1];
        var shown = list.filter(function (f) { return !liveFav.ids[f.id]; });
        var key = shown.map(function (f) { return f.id; }).join(',');
        var edges = liveFav.edges;
        React.useEffect(function () {
            var missing = shown.some(function (f) { return !favUsers[f.id]; });
            if (!key || !missing && Date.now() - favFetched < 30000) return;
            var live = true;
            fetch('https://gql.twitch.tv/gql', {method: 'POST', headers: {'Client-ID': GQL_CLIENT, 'Content-Type': 'application/json'},
                body: JSON.stringify({query: 'query($ids:[ID!]){users(ids:$ids){id login displayName profileImageURL(width:70) stream{id}}}',
                    variables: {ids: key.split(',')}})})
                .then(function (r) { return r.json(); })
                .then(function (j) {
                    favFetched = Date.now();
                    (j && j.data && j.data.users || []).forEach(function (u) { if (u) favUsers[u.id] = u; });
                    if (live) setLoaded(favFetched);
                })
                .catch(function () {});
            return function () { live = false; };
        }, [key, edges]);
        if (!shown.length) return null;
        var h = React.createElement;
        return h(RN.ScrollView, {horizontal: true, showsHorizontalScrollIndicator: false, contentContainerStyle: favStyles.strip},
            shown.map(function (f) {
                var user = favUsers[f.id] || {}, login = user.login || f.login, onAir = !!user.stream;
                function open() {
                    if (openChannel) openChannel(login, undefined, f.id, false, 0, 0, false);
                    else RN.Linking.openURL('twitch://stream/' + login);
                }
                function menu() {
                    if (openMenu) openMenu({channelId: f.id, login: login, displayName: user.displayName || f.name, isLive: onAir,
                        gameName: undefined, isPartner: undefined});
                }
                return h(RN.Pressable, {key: f.id, style: favStyles.item, onPress: open, onLongPress: menu, accessibilityRole: 'button'},
                    h(RN.View, {style: [favStyles.avatar, onAir ? favStyles.onAir : favStyles.offAir]},
                        user.profileImageURL ? h(RN.Image, {source: {uri: user.profileImageURL}, style: favStyles.image}) : null),
                    label({type: 'caption', muted: !onAir}, user.displayName || f.name));
            }));
    }

    // The menu row: BottomSheet.Item as Twitch's own rows, right under "View profile".
    function withFavoriteItem(sheet, target, onClose) {
        var Item = sheets && sheets.BottomSheet && sheets.BottomSheet.Item;
        if (!Item || !stars.on || !stars.off || !React.isValidElement(sheet) || !target || target.channelId == null) return sheet;
        var on = isFavorite(String(target.channelId));
        function item(style) {
            return React.createElement(Item, {key: 'retwitch-favorite', icon: on ? stars.on : stars.off, style: style,
                testID: 'retwitch-favorite', onPress: function () {
                    setFavorite(target, !on);
                    if (onClose) onClose();
                }}, on ? config.text.unfavorite : config.text.favorite);
        }
        try {
            return insertAfterProfile(sheet, item) || React.cloneElement(sheet, null, item(undefined), sheet.props.children);
        } catch (e) {
            return sheet;
        }
    }

    function insertAfterProfile(element, item) {
        if (!React.isValidElement(element)) return null;
        var kids = React.Children.toArray(element.props.children);
        for (var i = 0; i < kids.length; i++) {
            var kid = kids[i];
            if (React.isValidElement(kid) && kid.props.testID === 'following-channel-menu-profile') {
                kids.splice(i + 1, 0, item(kid.props.style));
                return React.cloneElement.apply(null, [element, null].concat(kids));
            }
            var inner = insertAfterProfile(kid, item);
            if (inner) {
                kids[i] = inner;
                return React.cloneElement.apply(null, [element, null].concat(kids));
            }
        }
        return null;
    }

    // The list content has its own 16 dp sides already.
    var favStyles = {
        header: {flexDirection: 'row', alignItems: 'center', paddingTop: 12, paddingBottom: 8},
        star: {color: '#ffc94d', fontSize: 20, marginRight: 8},
        strip: {paddingVertical: 6},
        item: {width: 64, alignItems: 'center', marginHorizontal: 2},
        avatar: {width: 44, height: 44, borderRadius: 22, marginBottom: 4, overflow: 'hidden', backgroundColor: '#35353b'},
        image: {width: 44, height: 44},
        onAir: {borderWidth: 2, borderColor: '#eb0400'},
        offAir: {opacity: 0.45},
        divider: {height: 1, marginTop: 6, marginBottom: 2, backgroundColor: '#2f2f35'}
    };

    // A channel page shelf the viewer hid: test(props) says when. The tracked shelf around it asks the same.
    function hiddenShelf(original, test) {
        var wrapped = function (props) { return test(props) ? null : React.createElement(original, props); };
        wrapped.retwitchHidden = test;
        return wrapped;
    }

    var adapters = {
        // Favorites go first among live channels; the section header and the strip are drawn around them below.
        useFollowingTabData: function (original) {
            return function () {
                var data = original.apply(this, arguments);
                var list = useFavorites();
                var edges = data && data.liveEdges;
                var sorted = React.useMemo(function () { return favoritesFirst(edges, list); }, [edges, list]);
                var result = React.useMemo(function () {
                    return sorted.edges === edges ? data : Object.assign({}, data, {liveEdges: sorted.edges});
                }, [data, sorted]);
                liveFav = sorted;
                return result;
            };
        },
        // With live favorites, their rows come first and Twitch's header ("Live channels", sort) moves below them:
        // TrackedFollowingChannelRow draws it after the last favorite. Without, the section stands above the header.
        FollowingLiveHeader: function (original) {
            LiveHeader = original;
            return function (props) {
                var list = useFavorites();
                liveHeaderProps = props;
                if (!list.length) return React.createElement(original, props);
                if (liveFav.last) return React.createElement(FavoritesHeader);
                return React.createElement(React.Fragment, null, React.createElement(FavoritesHeader),
                    React.createElement(FavoritesStrip), React.createElement(RN.View, {style: favStyles.divider}),
                    React.createElement(original, props));
            };
        },
        TrackedFollowingChannelRow: function (original) {
            return function (props) {
                useFavorites();
                if (props.onOpenMenu) openMenu = props.onOpenMenu;
                var row = React.createElement(original, props);
                var broadcaster = props.stream && props.stream.broadcaster;
                // cellKey: live rows only, mod channels have their own list.
                if (!broadcaster || String(broadcaster.id) !== liveFav.last || String(props.cellKey).indexOf('live-row-') !== 0) {
                    return row;
                }
                return React.createElement(React.Fragment, null, row, React.createElement(FavoritesStrip),
                    liveFav.rest > 0 && LiveHeader && liveHeaderProps ? React.createElement(React.Fragment, null,
                        React.createElement(RN.View, {style: favStyles.divider}),
                        React.createElement(LiveHeader, liveHeaderProps)) : null);
            };
        },
        // Called as a function, its hooks become this component's: the returned sheet gets the favorite row.
        FollowingChannelMenu: function (original) {
            return function (props) {
                useFavorites();
                return withFavoriteItem(original(props), props.target, props.onClose);
            };
        },
        // The channel page's ⋮ menu (React Native page; feedback idea #64): the star goes first. Called as a function
        // so its hooks are this component's, like FollowingChannelMenu.
        ChannelProfileOverflowMenu: function (original) {
            return function (props) {
                useFavorites();
                var menu = original(props), p = props.profile;
                if (!overflowItem || !stars.on || !stars.off || !React.isValidElement(menu) || !p || p.id == null) return menu;
                var on = isFavorite(String(p.id));
                var item = React.createElement(overflowItem, {key: 'retwitch-favorite', icon: on ? stars.on : stars.off,
                    label: on ? config.text.unfavorite : config.text.favorite, testID: 'retwitch-favorite',
                    onPress: function () {
                        setFavorite({channelId: p.id, login: p.login, displayName: p.displayName}, !on);
                    }});
                try {
                    return React.cloneElement.apply(null, [menu, null, item].concat(React.Children.toArray(menu.props.children)));
                } catch (e) {
                    return menu;
                }
            };
        },
        // Only to keep its channel opener for the friends block and the favorites strip.
        FollowingTab: function (original) {
            return function (props) {
                if (props.onOpenChannel) openChannel = props.onOpenChannel;
                return React.createElement(original, props);
            };
        },
        // Categories always come right after the live list: the friends block goes in front of them.
        FollowingCategories: function (original) {
            return function (props) {
                return React.createElement(React.Fragment, null,
                    React.createElement(Friends), React.createElement(original, props));
            };
        },
        // freeScroll: the Live tab and its categories. Paged feeds keep the stock card.
        FeedStreamItem: function (original) {
            return React.memo(function (props) {
                return React.createElement(props.freeScroll === true ? Row : original, props);
            }, original.compare);
        },
        // An ad slot is a blank near-full-screen cell until the ad store drops it, and that waits for the feed to
        // turn interactive: 20 s (useDeferUntilInteractive's failsafe) without the stock card's player.
        // Lengths for feedItemLayout. Only the free-scroll feed uses this renderer; with compactLive all of it is Row.
        FreeScrollCellRenderer: function (original) {
            cellRenderer = function (props) {
                var latest = React.useRef(props);
                latest.current = props;
                var onLayout = React.useCallback(function (e) {
                    var p = latest.current, layout = e.nativeEvent.layout;
                    noteCell(p.cellKey, p.index, layout);
                    var node = p.item && p.item.node;
                    if (node && node.content && node.content.broadcaster && layout.height > 0) rowLength = layout.height;
                    if (p.onLayout) p.onLayout(e);
                }, []);
                return React.createElement(original, Object.assign({}, props, {onLayout: onLayout}));
            };
            return cellRenderer;
        },
        FeedAdItem: function (original) {
            return function (props) {
                return props.freeScroll === true ? null : React.createElement(original, props);
            };
        },
        FollowingChannelRow: function () {
            return BigCard;
        },
        RecapsShelfHeader: function () {
            return function () { return null; };
        },
        // The feed measures the shelf only above 0 px, so nothing re-measures an empty one: useStoriesShelf below
        // zeroes the height itself.
        StoriesEmptyState: function () {
            return function () {
                React.useEffect(function () {
                    setStoriesEmpty(true);
                    return function () { setStoriesEmpty(!!config.hideStories); };
                }, []);
                return null;
            };
        },
        // The gesture starts a reveal at any measured shelf height and refreshes only without a shelf. An empty one
        // counts as none: the first pull refreshes instead of opening an empty shelf.
        useStoriesShelf: function (original) {
            return function (args) {
                var state = React.useState(storiesEmpty), empty = state[0], setEmpty = state[1];
                React.useEffect(function () {
                    shelfListeners.push(setEmpty);
                    setEmpty(storiesEmpty);
                    return function () { shelfListeners.splice(shelfListeners.indexOf(setEmpty), 1); };
                }, []);
                var shelf = original(empty && args ? Object.assign({}, args, {shelfPresent: false}) : args);
                var setHeight = shelf && shelf.setShelfHeight;
                React.useEffect(function () {
                    if (empty && setHeight) setHeight(0);
                }, [empty, setHeight]);
                return shelf;
            };
        },
        // The top chrome (FeedTopChrome): the shelf above the tabs and the story avatars in the tab row. A hidden
        // shelf counts as an empty one for the pull gesture (useStoriesShelf): it refreshes instead of revealing.
        StoriesShelf: function () {
            return function () { return null; };
        },
        // Channel page, Home tab (ChannelProfileHome). Latest Stream Recaps.
        ChannelProfileRecapsShelf: function (original) {
            return hiddenShelf(original, function () { return true; });
        },
        // Featured Clips; "Clips I've Made" (kind curated) stays.
        ChannelProfileClipShelf: function (original) {
            return hiddenShelf(original, function (props) { return props.kind === 'featured'; });
        },
        // "<name>'s Streams by Category" and "<name> Suggests These Streamers" (or "Other <team> Channels").
        ChannelProfileHomeShelf: function (original) {
            return function (props) {
                if (config.hideProfileCategories && props.testID === 'channel-profile-home-categories'
                    || config.hideProfileStreamers && props.testID === 'channel-profile-home-streamers') return null;
                return React.createElement(original, props);
            };
        },
        // Recaps and clips sit in one of these: an empty one would keep its slot in the page's gap.
        ChannelProfileTrackedShelf: function (original) {
            return function (props) {
                var child = props.children, test = child && child.type && child.type.retwitchHidden;
                if (test && test(child.props || {})) return null;
                return React.createElement(original, props);
            };
        },
        CollapsedStoriesIcons: function () {
            return function () { return null; };
        },
        // Following, Live, Clips; the launch cover's placeholder row too. The bar keeps its top inset, so the feed
        // still re-measures it (only heights above 0 count).
        FeedTabsRow: function () {
            return function () { return null; };
        },
        // FeedTabsRow (Following, Live, Clips) draws this under a Tabs row with its own indicator hidden. With a large
        // font the row overflows and Tabs scrolls the active tab to the middle of it, but this one sits outside the
        // ScrollView and takes the unscrolled x. Each tab moves by the scroll Tabs gives it when active, so a swipe
        // also ends where the tab will be.
        CustomTabIndicator: function (original) {
            return function (props) {
                var tabs = props.measurements, width = props.width;
                var shifted = tabs && width > 0 && tabs.length >= props.tabCount && !RN.I18nManager.isRTL
                    ? scrolledTabs(tabs, width) : null;
                return React.createElement(original, shifted ? Object.assign({}, props, {measurements: shifted}) : props);
            };
        },
        // shortcut: the header icon is what Twitch checks first (isClipsShelf), a clips shelf without it stays.
        FeedShelfContainer: function (original) {
            return function (props) {
                return props.node.headerAccessory === 'CLIP_ICON' ? null : React.createElement(original, props);
            };
        }
    };

    // Tabs' own scrollTo: the tab's middle to the row's, within the content. Null when nothing scrolls.
    function scrolledTabs(tabs, width) {
        var last = tabs[tabs.length - 1];
        if (!last || !tabs.every(Boolean)) return null;
        var maxScroll = last.x + last.width + TABS_PADDING_END - width;
        if (maxScroll <= 0) return null;
        return tabs.map(function (tab) {
            var scroll = Math.min(maxScroll, Math.max(0, tab.x + tab.width / 2 - width / 2));
            return {x: tab.x - scroll, width: tab.width};
        });
    }

    function scan(exports) {
        if (exports === null || (typeof exports !== 'object' && typeof exports !== 'function')) return;
        if (fav) {
            // One module per icon: the one with IconVerified has no stars.
            if (!stars.on && 'IconStar' in exports) stars.on = exports.IconStar;
            if (!stars.off && 'IconStarHollow' in exports) stars.off = exports.IconStarHollow;
            if (!sheets && 'BottomSheet' in exports) sheets = exports;
            if (!overflowItem && 'OverflowMenuItem' in exports) overflowItem = exports.OverflowMenuItem;
        }
        // `in`: react-native's exports are lazy getters, reading one would load that module here.
        if (!React && 'memo' in exports && 'useState' in exports) React = exports;
        else if (!RN && 'AppRegistry' in exports && 'Pressable' in exports) RN = exports;
        else if (!core && 'CoreText' in exports) core = exports;
        else if (!icons && 'IconVerified' in exports) icons = exports;
        else if (!host && 'hostFeedContentReady' in exports) host = exports;
        // react-native's Image: a function component named Image whose statics carry getSize and queryCache. The native
        // image loader module's default has both too (an object), and so does Image's inner forwardRef: neither is
        // what RN.Image gives, and the loader patched first left every preview bare.
        else if (config.uptime && !imagePatched && 'default' in exports && typeof exports.default === 'function'
            && exports.default.displayName === 'Image'
            && typeof exports.default.queryCache === 'function' && typeof exports.default.getSize === 'function') {
            imagePatched = true;
            patchImage(exports);
            if (global.console) console.log('[ReTwitch] rn: Image');
        }
        // react-native's FlatList module: its default export, the one class with _pushMultiColumnViewable.
        else if (config.compactLive && !flatListPatched && 'default' in exports && typeof exports.default === 'function'
            && exports.default.prototype && '_pushMultiColumnViewable' in exports.default.prototype) {
            flatListPatched = true;
            patchFlatList(exports.default);
            if (global.console) console.log('[ReTwitch] rn: FlatList');
        }
        else Object.keys(targets).forEach(function (name) {
            if (targets[name] && name in exports) {
                targets[name] = false;
                exports[name] = adapters[name](exports[name]);
                if (global.console) console.log('[ReTwitch] rn: ' + name);
            }
        });
    }

    Object.defineProperty(global, '__d', {
        configurable: true,
        get: function () { return define; },
        set: function (metroDefine) {
            define = function (factory) {
                var args = Array.prototype.slice.call(arguments);
                args[0] = function () {
                    var result = factory.apply(this, arguments);
                    scan(arguments[4].exports);
                    return result;
                };
                return metroDefine.apply(this, args);
            };
        }
    });
})(globalThis);
