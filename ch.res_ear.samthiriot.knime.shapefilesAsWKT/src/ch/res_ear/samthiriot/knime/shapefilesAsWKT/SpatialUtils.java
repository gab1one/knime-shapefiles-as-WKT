package ch.res_ear.samthiriot.knime.shapefilesAsWKT;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Random;

import org.geotools.data.DataStore;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.shapefile.ShapefileDataStoreFactory;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.factory.CommonFactoryFinder;
import org.geotools.factory.GeoTools;
import org.geotools.feature.FeatureIterator;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.feature.type.BasicFeatureTypes;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultGeographicCRS;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataColumnSpec;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.def.IntCell;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.util.FileUtil;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.filter.Filter;
import org.opengis.filter.FilterFactory2;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.ReferenceIdentifier;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

// see http://docs.geotools.org/latest/userguide/tutorial/feature/csv2shp.html

/**
 * Utilities to manipulate spatial data within KNIME.
 * 
 * @author Samuel Thiriot (EIFER)
 *
 */
public class SpatialUtils {

	public static final String GEOMETRY_COLUMN_NAME = "the_geom";
	
	public static final String PROPERTY_CRS_CODE = "crs code";
	public static final String PROPERTY_CRS_WKT = "crs WKT";
	
	
	public static String getDefaultCRSString() {
		return "EPSG:4326";
	}
	
	public static String getStringForCRS(CoordinateReferenceSystem crs) {
		try {
		    ReferenceIdentifier id = crs.getIdentifiers().iterator().next();
		    return id.getCodeSpace()+":"+id.getCode();
		} catch (NoSuchElementException e) {
			return crs.getName().getCodeSpace()+":"+crs.getName().getCode();
		}

	}
	
	public static CoordinateReferenceSystem getCRSforString(String s) {
		if (s == null)
			throw new IllegalArgumentException("No CRS provided");
		
		try {
			return CRS.decode(s);
		} catch (FactoryException e1) {
			e1.printStackTrace();
			throw new IllegalArgumentException("unable to decode CRS from string: "+s);
		} catch (NullPointerException e2) {
			throw new IllegalArgumentException("This string does not contains any CRS: "+s);
		}
	}
	
	/**
	 * Takes a column of a sample supposed to be in WKT format, 
	 * and tries to detect using the 50 first lines 
	 * which geometry type it is: Point, Polyline, etc.
	 * 
	 * @param sample
	 * @param colNameGeom
	 * @return
	 */
	public static Class<?> detectGeometryClassFromData(
							BufferedDataTable sample,
							String colNameGeom) {
			
		final int SAMPLE = 50;
		
		final int idxColGeom = sample.getDataTableSpec().findColumnIndex(colNameGeom);

        GeometryFactory geomFactory = JTSFactoryFinder.getGeometryFactory( null );
        WKTReader reader = new WKTReader(geomFactory);
        
		List<Geometry> foundGeometries = new ArrayList<Geometry>(SAMPLE);
		
    	Iterator<DataRow> itRows = sample.iterator();
    	long id = 0;
    	while (itRows.hasNext()) {
        	DataRow currentRow = itRows.next();
        	
        	DataCell cellGeom = currentRow.getCell(idxColGeom);
        	
        	if (cellGeom.isMissing()) {
        		continue;
        	}
        
        	Geometry g;
			try {
				g = reader.read(cellGeom.toString());
				foundGeometries.add(g);
			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				// ignore it
			}
        	
        	
        	if (foundGeometries.size() >= SAMPLE) {
        		break;
        	}
        	
    	}
    	
    	if (foundGeometries.isEmpty()) {
    		throw new IllegalArgumentException("no geometry found in column "+colNameGeom);
    	}
        
    	// check if all the geometries are the same
    	Class<?> classFirst = foundGeometries.get(0).getClass();
    	if (foundGeometries.stream().anyMatch( g -> !g.getClass().equals(classFirst))) {
    		throw new IllegalArgumentException("not all the geometry types are the same");
    	}
    	
    	return classFirst;
	}
	
	
	public static DataStore createDataStore() {
        return SpatialUtils.createTmpDataStore(true);
	}
	
	public static SimpleFeatureType createGeotoolsType(
			BufferedDataTable sample,
			String colNameGeom,
			String featureName,
			CoordinateReferenceSystem crs
			) {
		
		SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName(featureName);
        builder.setCRS(crs); 
        
        Class<?> geomClassToBeStored = detectGeometryClassFromData(sample, colNameGeom);
        
        // add attributes in order
        builder.add(
        		SpatialUtils.GEOMETRY_COLUMN_NAME, 
        		geomClassToBeStored
        		);
        // TODO later
        builder.add("id", Integer.class);

        // build the type
        final SimpleFeatureType type = builder.buildFeatureType();

        return type;
	}
	
	public static SimpleFeatureStore createFeatureStore(
			BufferedDataTable sample,
			DataStore datastore,
			SimpleFeatureType type,
			String featureName) throws IOException {
        
		
		try {
			datastore.getSchema(type.getName());
		} catch (IOException e) {
			datastore.createSchema(type);	
		}
		
        //System.out.println(datastore.getNames().get(0));
        
        // datastore.getSchema(featureName)
		SimpleFeatureSource featureSource = datastore.getFeatureSource(datastore.getNames().get(0));
        if (!(featureSource instanceof SimpleFeatureStore)) {
            throw new IllegalStateException("Modification not supported");
        }
        SimpleFeatureStore featureStore = (SimpleFeatureStore) featureSource;
        
        return featureStore;
        
	}


	private static class AddRowsRunnable implements Runnable {

		private final BufferedDataTable sample; 
		
		final static int BUFFER = 50000;
		
		private List<SimpleFeature> toStore = new ArrayList<>(BUFFER);
		private final int idxColGeom; 
		private final WKTReader reader;
		private final SimpleFeatureBuilder featureBuilder;
		private final SimpleFeatureStore featureStore;
		private final SimpleFeatureType type;
		private final ExecutionMonitor execProgress;
		private final boolean assumeId;
		
		public AddRowsRunnable(
				BufferedDataTable sample, 
				int idxColGeom,
				SimpleFeatureStore featureStore,
				SimpleFeatureType type,
				ExecutionMonitor execProgress,
				boolean assumeId) {
			this.sample = sample;
			this.idxColGeom = idxColGeom;
			this.featureStore = featureStore;
			this.type = type;
			this.execProgress = execProgress;
			this.assumeId = assumeId;
	        this.featureBuilder = new SimpleFeatureBuilder(type);

			GeometryFactory geomFactory = JTSFactoryFinder.getGeometryFactory( null );
	        reader = new WKTReader(geomFactory);
		}
		
		@Override
		public void run() {
			
			toStore.clear();
			this.execProgress.setProgress(.0);
			
			double total = (double)this.sample.size();
			long current = 0;
			
			System.out.println(Thread.currentThread().getName()+"  total "+this.sample.size());
			Iterator<DataRow> itRow = sample.iterator();
			
        	while (itRow.hasNext()) {
        		DataRow currentRow = itRow.next();
        		
        		DataCell cellGeom = currentRow.getCell(idxColGeom);
        		IntCell cellIdx = null;
        		if (assumeId) {
        			cellIdx = (IntCell)currentRow.getCell(0);
        			if (cellIdx == null) {
                		System.out.println("ignoring the null cell id");
                		continue;
            		}
        		} 
        		
            	if (cellGeom.isMissing()) {
            		System.out.println("ignoring one line");
            		continue; // ignore data with missing elements
            	}
            	
            	try {
    				Geometry geom = reader.read(cellGeom.toString());
    				featureBuilder.add(geom);
                    if (cellIdx != null) featureBuilder.add(cellIdx.getIntValue());

    			} catch (ParseException e) {
    				// TODO Auto-generated catch block
    				e.printStackTrace();
    				throw new RuntimeException(e);
    			}
            	
                SimpleFeature feature = featureBuilder.buildFeature(null);
                
                toStore.add(feature);
                
				if (toStore.size() >= BUFFER) {
	    			System.out.println(Thread.currentThread().getName()+" Store buffer "+toStore.size());

					storeBufferedSpatialData();
				}
 
        		if (current % 500 == 0) {
	        		// TODO not always
	        		this.execProgress.setProgress((double)current/total);
	        		try {
						this.execProgress.checkCanceled();
					} catch (CanceledExecutionException e) {
						// TODO???
						return;
					} 
	        		
	    			//System.out.println(Thread.currentThread().getName()+" "+current+"/"+this.split.estimateSize());

        		}
        		
        		current++;

        	}
        	
        	// store data remaining in the buffer
        	storeBufferedSpatialData();
    		this.execProgress.setProgress(1.0);

		}
		
		private void storeBufferedSpatialData() {
			
			if (toStore.isEmpty())
				return;
			
        	try {
				featureStore.addFeatures( new ListFeatureCollection( type, toStore) );
			} catch (IOException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				throw new RuntimeException("error when storing features in the store", e);
			}
        	toStore.clear();

		}
		
		
	}


	public static Runnable decodeAsFeaturesRunnable(
			BufferedDataTable sample,
			String colNameGeom,
			ExecutionMonitor execProgress,
			DataStore datastore,
			String featureName,
			CoordinateReferenceSystem crs,
			boolean assumeId
			) throws IOException {
		
		SimpleFeatureType type = createGeotoolsType(sample, colNameGeom, featureName, crs);
		SimpleFeatureStore store = createFeatureStore(sample, datastore, type, featureName);

		final int idxColGeom = sample.getDataTableSpec().findColumnIndex(colNameGeom);

              
        return new AddRowsRunnable(sample, idxColGeom, store, type, execProgress, assumeId);
        
		
	}
		
	
		
	/**
	 * Converts a datatable to a collection of spatial features
	 * by decoding a string column as KWT
	 * 
	 * TODO parallel processing
	 *  
	 * @param sample
	 * @param colNameGeom
	 * @return
	 * @throws IOException 
	 * @throws CanceledExecutionException 
	 */
	public static DataStore decodeAsFeatures(
			BufferedDataTable sample,
			String colNameGeom,
			ExecutionMonitor execProgress) throws IOException, CanceledExecutionException {
		
		execProgress.setMessage("creating a file datastore");
		
        DataStore datastore = SpatialUtils.createTmpDataStore(true);
        
		final int idxColGeom = sample.getDataTableSpec().findColumnIndex(colNameGeom);
        
		// 
        //SimpleFeatureCollection collec = FeatureCollections.newCollection();
        SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName("Geom");
        builder.setCRS(DefaultGeographicCRS.WGS84); // <- Coordinate reference system
        
        // add attributes in order
        builder.add(
        		SpatialUtils.GEOMETRY_COLUMN_NAME, 
        		detectGeometryClassFromData(sample, colNameGeom)
        		);


        builder.add("id", Integer.class);

        // build the type
        final SimpleFeatureType type = builder.buildFeatureType();

        datastore.createSchema(type);

        SimpleFeatureSource featureSource = datastore.getFeatureSource(datastore.getNames().get(0));
        if (!(featureSource instanceof SimpleFeatureStore)) {
            throw new IllegalStateException("Modification not supported");
        }
        SimpleFeatureStore featureStore = (SimpleFeatureStore) featureSource;

        		
        SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(type);
        GeometryFactory geomFactory = JTSFactoryFinder.getGeometryFactory( null );
        WKTReader reader = new WKTReader(geomFactory);
        
        List<SimpleFeature> features = new ArrayList<>();

    	Iterator<DataRow> itRows = sample.iterator();
    	long id = 0;
    	final int BUFFER = 100;
    	List<SimpleFeature> toStore = new ArrayList<>(BUFFER);
    	while (itRows.hasNext()) {
        	DataRow currentRow = itRows.next();
        	
        	DataCell cellGeom = currentRow.getCell(idxColGeom);
        	
        	if (cellGeom.isMissing()) {
        		continue;
        	}
        	
        	try {
				Geometry geom = reader.read(cellGeom.toString());
				featureBuilder.add(geom);
                featureBuilder.add(id++);

			} catch (ParseException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
				throw new RuntimeException(e);
			}
        	
            SimpleFeature feature = featureBuilder.buildFeature(null);
            features.add(feature);
            
            toStore.add(feature);
            
            if (toStore.size() >= BUFFER) {
            	featureStore.addFeatures( new ListFeatureCollection( type, toStore));
            	toStore.clear();
            }
            
            if (id % 10 == 0) {
        		execProgress.setProgress((double)id / sample.size(), "processing entity "+id);
        		execProgress.checkCanceled();
            }

        }
        if (!toStore.isEmpty()) {
        	featureStore.addFeatures( new ListFeatureCollection( type, toStore));
        }

        execProgress.setProgress(1.0);
        
        return datastore;

	}
	
	/**
	 * Create a temporary datastore
	 * @return
	 */
	public static DataStore createTmpDataStore(boolean createSpatialIndex) {
        File file;
		try {
			file = FileUtil.createTempFile("datastore", ".shp", true);
		} catch (IOException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			throw new RuntimeException("unable to create a geotools datastore", e1);

		}
		
		return createDataStore(file, createSpatialIndex);
	}
	
	
	public static DataStore createDataStore(File file, boolean createSpatialIndex) {
        
		Map map = new HashMap();
		try {
			map.put( "url", file.toURI().toURL() );
			map.put("create spatial index", Boolean.valueOf(createSpatialIndex));

		} catch (MalformedURLException e1) {
			// TODO Auto-generated catch block
			e1.printStackTrace();
			throw new RuntimeException("unable to create a geotools datastore", e1);

		}
		DataStore dataStore = null;
		try {
			dataStore = new ShapefileDataStoreFactory().createNewDataStore(map);
			//dataStore = DataStoreFinder.getDataStore(map );
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new RuntimeException("unable to create a geotools datastore", e);
		}
		
		return dataStore;
	}
	
	
	/** 
	 * finds the entities of a datastore contained inside 
	 * a given geometry
	 * @param datastore1
	 * @param geom
	 * @return
	 */
	public static FeatureIterator<SimpleFeature> findEntitiesWithin(SimpleFeatureSource source, Geometry geom) {
		
		FilterFactory2 ff = CommonFactoryFinder.getFilterFactory2( GeoTools.getDefaultHints() );
		Filter filter = ff.within(ff.property( BasicFeatureTypes.GEOMETRY_ATTRIBUTE_NAME), ff.literal( geom ));
		
		FeatureIterator<SimpleFeature> fItt;
		try {
			fItt = source.getFeatures(filter).features();
		} catch (IOException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			throw new RuntimeException("error while loading entities",e);
		}
		return fItt;
	}
	
	public static FeatureIterator<SimpleFeature> findClosestNeighboorFixBuffer(
			Geometry geom,
			SimpleFeatureSource source,
			int buffer) {
		
		
		Geometry buffered = geom.buffer(buffer);
		
		return findEntitiesWithin(source, buffered);
		
	}

	public static SimpleFeature findClosestNeighboorVariableBuffer(
			Geometry geom,
			SimpleFeatureSource source,
			int maxBuffer) {
		
		int stepBuffer = maxBuffer/5;

		double shortestDistance = Double.MAX_VALUE;
		List<SimpleFeature> closestPoints = new LinkedList<SimpleFeature>();

		for (int bufferDistance = stepBuffer; bufferDistance <= stepBuffer*5+1; bufferDistance += stepBuffer) {

			FeatureIterator<SimpleFeature> itNeighboors = findClosestNeighboorFixBuffer(geom, source, bufferDistance);

			// compute distances
			while (itNeighboors.hasNext()) {
				SimpleFeature neighboor = itNeighboors.next();
				double distance = geom.distance((Geometry) neighboor.getAttribute(0));
				if (distance < shortestDistance) {
					shortestDistance = distance;
					closestPoints.clear();
					closestPoints.add(neighboor);
				} else if (distance == shortestDistance) {
					closestPoints.add(neighboor);
				}
			}
			
			itNeighboors.close();
			
			// if we found something, stop searching far
			if (!closestPoints.isEmpty()) {
				System.out.println("at distance "+bufferDistance+", found "+closestPoints.size()+" neighboors");
				break;
			}
			
		}
		
		// return the closest
		if (closestPoints.size() == 1) {
			return closestPoints.get(0);
		} else if (closestPoints.size() > 1) {
			// or one random
			Random random = new Random();
			System.err.println("selecting one random neighboor among "+closestPoints.size());
			return closestPoints.get(random.nextInt(closestPoints.size()));
		}
		
		// return null if nothing found
		return null;
		
	}
	
	
	private SpatialUtils() {

	}

	public static boolean hasCRS(DataColumnSpec columnSpec) {
		return columnSpec.getProperties().getProperty(PROPERTY_CRS_CODE) != null;
	}
	
	public static CoordinateReferenceSystem decodeCRSFromColumnSpec(DataColumnSpec columnSpec) {

		try {
			return SpatialUtils.getCRSforString(columnSpec.getProperties().getProperty(PROPERTY_CRS_CODE));
		} catch (IllegalArgumentException e) {
			try {
				return CRS.parseWKT(columnSpec.getProperties().getProperty(PROPERTY_CRS_WKT));
			} catch (FactoryException e1) {
				e1.printStackTrace();
				throw new IllegalArgumentException(
						"Unable to decode a coordinate reference system "+
						"from the code \""+columnSpec.getProperties().getProperty(PROPERTY_CRS_CODE)+"\""+
						" nor from WKT "+columnSpec.getProperties().getProperty(PROPERTY_CRS_WKT)); 
			}
		}
	}
	
	public static CoordinateReferenceSystem decodeCRS(DataTableSpec spec) {

		int idx = spec.findColumnIndex(GEOMETRY_COLUMN_NAME);
		if (idx < 0) 
			throw new IllegalArgumentException("No column for containing geometry "+GEOMETRY_COLUMN_NAME);
		
		DataColumnSpec columnSpec = spec.getColumnSpec(idx);
		
		try {
			return SpatialUtils.getCRSforString(columnSpec.getProperties().getProperty(PROPERTY_CRS_CODE));
		} catch (IllegalArgumentException e) {
			try {
				return CRS.parseWKT(columnSpec.getProperties().getProperty(PROPERTY_CRS_WKT));
			} catch (FactoryException e1) {
				e1.printStackTrace();
				throw new IllegalArgumentException(
						"Unable to decode a coordinate reference system "+
						"from the code \""+columnSpec.getProperties().getProperty(PROPERTY_CRS_CODE)+"\""+
						" nor from WKT "+columnSpec.getProperties().getProperty(PROPERTY_CRS_WKT)); 
			}
		}
	}


	public static boolean hasCRS(DataTableSpec dataTableSpec) {
		int idx = dataTableSpec.findColumnIndex(GEOMETRY_COLUMN_NAME);
		if (idx < 0)
			return false;
		return dataTableSpec.getColumnSpec(idx).getProperties().getProperty(PROPERTY_CRS_WKT) != null;
	}

	public static boolean hasGeometry(DataTableSpec dataTableSpec) {
		int idx = dataTableSpec.findColumnIndex(GEOMETRY_COLUMN_NAME);
		return (idx >= 0);
	}

}