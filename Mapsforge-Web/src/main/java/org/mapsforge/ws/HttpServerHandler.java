package org.mapsforge.ws;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.mapsforge.core.graphics.GraphicFactory;
import org.mapsforge.core.graphics.TileBitmap;
import org.mapsforge.core.model.Tile;
import org.mapsforge.core.util.MercatorProjection;
import org.mapsforge.map.awt.graphics.AwtGraphicFactory;
import org.mapsforge.map.datastore.MapDataStore;
import org.mapsforge.map.layer.cache.FileSystemTileCache;
import org.mapsforge.map.layer.cache.TileCache;
import org.mapsforge.map.layer.labels.TileBasedLabelStore;
import org.mapsforge.map.layer.renderer.DatabaseRenderer;
import org.mapsforge.map.layer.renderer.RendererJob;
import org.mapsforge.map.model.DisplayModel;
import org.mapsforge.map.model.FixedTileSizeDisplayModel;
import org.mapsforge.map.reader.MapFile;
import org.mapsforge.map.rendertheme.ExternalRenderTheme;
import org.mapsforge.map.rendertheme.XmlRenderTheme;
import org.mapsforge.map.rendertheme.rule.RenderThemeFuture;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObject;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.CharsetUtil;

/**
 * 
 * @author Raymond Wu
 */
public class HttpServerHandler  extends SimpleChannelInboundHandler<HttpObject> {

	// TX and TY range of a zoom level.
	static class TileRange {
		public int txMin;
		public int txMax;
		public int tyMin;
		public int tyMax;
		
		public TileRange(int txMin, int txMax, int tyMin, int tyMax) {
			this.txMin = txMin;
			this.txMax = txMax;
			this.tyMin = tyMin;
			this.tyMax = tyMax;
		}
	}
	
	// Your compiled map.
	private static final String HOME = System.getProperty("user.home");
	private static final String SAVE_PATH = HOME + "/osm-data/tilecache";
	private static final File MAP_PATH = new File(HOME + "/osm-data/taiwan-taco.map");
	private static final Pattern URI_PATTERN = Pattern.compile("^/([a-z]+)/(\\d+)/(\\d+)/(\\d+)$");
	private static final int CACHE_CAPACITY = 32768;
	private static final int TILE_SIZE = 256;
	
	// Renderer dependencies.
	private static final GraphicFactory GRAPHIC_FACTORY = AwtGraphicFactory.INSTANCE;
	private static final DisplayModel DISPLAY_MODEL = new FixedTileSizeDisplayModel(TILE_SIZE);
	private static final MapFile MAPDS = new MapFile(MAP_PATH);
	private static final HashMap<Byte, TileRange> TILE_RANGE_TABLE = new HashMap<>();
	private static final HashMap<String, TileCache> TILE_CACHE_TABLE = new HashMap<>();
	private static final HashMap<String, RenderThemeFuture> RENDER_THEME_FUTURE_TABLE = new HashMap<>();
	
	static {
		double lngMin = MAPDS.boundingBox().minLongitude;
		double lngMax = MAPDS.boundingBox().maxLongitude;
		double latMin = MAPDS.boundingBox().minLatitude;
		double latMax = MAPDS.boundingBox().maxLatitude;	
		
		int txMin, txMax, tyMin, tyMax;
		for (byte z=7; z<=18; z++) {
			txMin = MercatorProjection.longitudeToTileX(lngMin, z);
			txMax = MercatorProjection.longitudeToTileX(lngMax, z);
			tyMin = MercatorProjection.latitudeToTileY(latMax, z);
			tyMax = MercatorProjection.latitudeToTileY(latMin, z);
			TILE_RANGE_TABLE.put(z, new TileRange(txMin, txMax, tyMin, tyMax));
		}
	}
	
	/**
	 * http://localhost:20480/classic/16/54898/28048
	 */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, HttpObject msg) throws Exception {
        if (msg instanceof HttpRequest) {
        		ByteBuf tileContent = null;
        		StringBuilder errReason = new StringBuilder();
        		HttpResponseStatus httpResponseStatus = HttpResponseStatus.OK;
        		
        		// Request validation.
            HttpRequest httpRequest = (HttpRequest)msg;

            if (httpRequest.method().name().equals("GET")) {
            		// Pattern check & extract fields.
                Matcher matcher = URI_PATTERN.matcher(httpRequest.uri());                
                if (matcher.find()) {
                		String themeName = matcher.group(1);
                		byte zoom = Byte.valueOf(matcher.group(2));
                		int tx = Integer.valueOf(matcher.group(3));
                		int ty = Integer.valueOf(matcher.group(4));
                		
                		if (!(themeName.equals("default") || themeName.equals("classic"))) {
                			errReason.append("Theme field allows default or classic only.\n");
                		}
                		
                		if (zoom < 7 || zoom > 18) {
                			errReason.append("Zoom field must be between 7~18.\n");
                		} else {
                			TileRange tileRange = TILE_RANGE_TABLE.get(zoom);
                			
                			if (tx < tileRange.txMin || tx > tileRange.txMax) {
                				String hint = String.format(
            						"TILE_X in zoom level %d must be between %d~%d.",
            						zoom, tileRange.txMin, tileRange.txMax
            					);
                				errReason.append(hint);
                			}
                			
                			if (ty < tileRange.tyMin || ty > tileRange.tyMax) {
                				String hint = String.format(
            						"TILE_Y in zoom level %d must be between %d~%d.",
            						zoom, tileRange.tyMin, tileRange.tyMax
            					);
                				errReason.append(hint);
                			}
                		}
                		
                		if (errReason.length() == 0) {
                			// 200
                			tileContent = getTile(themeName, zoom, tx, ty);
                		} else {
                			// 406
                			httpResponseStatus = HttpResponseStatus.NOT_ACCEPTABLE;
                		}
                } else {
                		// 400
                		errReason.append("URI must be in this format /{THEME}/{ZOOM}/{TILE_X}/{TILE_Y}.\n");
                		httpResponseStatus = HttpResponseStatus.BAD_REQUEST;
                }
            } else {
            		// 405
            		errReason.append("GET method is allowed only.\n");
            		httpResponseStatus = HttpResponseStatus.METHOD_NOT_ALLOWED;
            }
            
            // Response tile or error message. 
            
            FullHttpResponse response;
            
            if (tileContent != null) {
	    			response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpResponseStatus, tileContent);
	        		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "image/png");
	            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, tileContent.readableBytes());
            } else {
            		ByteBuf msgContent = Unpooled.copiedBuffer(errReason, CharsetUtil.UTF_8);
            		response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, httpResponseStatus, msgContent);
            		response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            		response.headers().set(HttpHeaderNames.CONTENT_LENGTH, msgContent.readableBytes());
            }
            
            ctx.writeAndFlush(response);
            ctx.channel().close();
        }
    }
    
    private synchronized TileCache getTileCache(String themeName) {
    		if (TILE_CACHE_TABLE.containsKey(themeName)) {
    			return TILE_CACHE_TABLE.get(themeName);
    		} else {
    			File cacheDir = new File(SAVE_PATH, themeName);
    			TileCache tileCache = new FileSystemTileCache(CACHE_CAPACITY, cacheDir, GRAPHIC_FACTORY, false);
    			TILE_CACHE_TABLE.put(themeName, tileCache);
    	    		return tileCache;
    		}
    }
    
    private synchronized RenderThemeFuture getRenderThemeFuture(String themeName) throws FileNotFoundException {
    		if (RENDER_THEME_FUTURE_TABLE.containsKey(themeName)) {
    			return RENDER_THEME_FUTURE_TABLE.get(themeName);
    		} else {
    			String themePath = String.format("res/themes/%s/theme.xml", themeName);
    			XmlRenderTheme theme = new ExternalRenderTheme(themePath);
    			RenderThemeFuture rtf = new RenderThemeFuture(GRAPHIC_FACTORY, theme, DISPLAY_MODEL);
    			RENDER_THEME_FUTURE_TABLE.put(themeName, rtf);
    			new Thread(rtf).start();
    			return rtf;
    		}
    };
    
    private RendererJob createJob(MapDataStore mapData, RenderThemeFuture rtf, byte zoom, int tx, int ty) {
		Tile tile = new Tile(tx, ty, zoom, TILE_SIZE);
		RendererJob theJob = new RendererJob(tile, mapData, rtf, DISPLAY_MODEL, 1.0f, false, false);
		return theJob;
	}
    
    private ByteBuf getTile(String themeName, byte zoom, int tx, int ty) throws Exception {
    		long begin = System.currentTimeMillis();

    		String action;
    		RenderThemeFuture rtf = getRenderThemeFuture(themeName);
    		TileCache tileCache = getTileCache(themeName);
    		RendererJob theJob = createJob(MAPDS, rtf, zoom, tx, ty);
    		
    		if (!tileCache.containsKey(theJob)) {
    			TileBasedLabelStore tileBasedLabelStore = new TileBasedLabelStore(tileCache.getCapacityFirstLevel());
    			DatabaseRenderer renderer = new DatabaseRenderer(MAPDS, GRAPHIC_FACTORY, tileCache, tileBasedLabelStore, true, true, null);
    			TileBitmap tb = renderer.executeJob(theJob);
    			tileCache.put(theJob, tb);
    			
    			action = "Draw tile";
    		} else {
    			// TODO: Use logger.
    			action = "Use cached tile";
    		}
		
		// Load tile as a Netty buffer.
		String TILE_PATH = String.format("%s/%s/%d/%d/%d.tile", SAVE_PATH, themeName, zoom, tx, ty);
		InputStream tile = new FileInputStream(TILE_PATH);
		ByteBuf buffer = Unpooled.buffer();
		buffer.writeBytes(tile, tile.available());
		
		double elapsed = (System.currentTimeMillis() - begin) / 1000.0;
		
		// TODO: Use logger.
		System.out.printf("%s %s/%d/%d/%d. (%.3f seconds used)\n", action, themeName, zoom, tx, ty, elapsed);
		
		return buffer;
    }
    
}