/*******************************************************************************
 * Copyright (c) 2019 EIfER[1] (European Institute for Energy Research).
 * This program and the accompanying materials
 * are made available under the terms of the GNU GENERAL PUBLIC LICENSE
 * which accompanies this distribution, and is available at
 * https://www.gnu.org/licenses/gpl-3.0.html
 *
 * Contributors:
 *     Samuel Thiriot - original version and contributions
 *******************************************************************************/
package ch.res_ear.samthiriot.knime.shapefilesaswkt.write.write_to_db;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.geotools.data.DataStore;
import org.geotools.data.DataStoreFinder;
import org.geotools.data.DefaultTransaction;
import org.geotools.data.Transaction;
import org.geotools.data.collection.ListFeatureCollection;
import org.geotools.data.simple.SimpleFeatureCollection;
import org.geotools.data.simple.SimpleFeatureSource;
import org.geotools.data.simple.SimpleFeatureStore;
import org.geotools.feature.simple.SimpleFeatureBuilder;
import org.geotools.feature.simple.SimpleFeatureTypeBuilder;
import org.geotools.geometry.jts.JTSFactoryFinder;
import org.knime.core.data.DataCell;
import org.knime.core.data.DataRow;
import org.knime.core.data.DataTableSpec;
import org.knime.core.data.container.CloseableRowIterator;
import org.knime.core.node.BufferedDataTable;
import org.knime.core.node.CanceledExecutionException;
import org.knime.core.node.ExecutionContext;
import org.knime.core.node.ExecutionMonitor;
import org.knime.core.node.InvalidSettingsException;
import org.knime.core.node.NodeModel;
import org.knime.core.node.NodeSettingsRO;
import org.knime.core.node.NodeSettingsWO;
import org.knime.core.node.defaultnodesettings.SettingsModelBoolean;
import org.knime.core.node.defaultnodesettings.SettingsModelIntegerBounded;
import org.knime.core.node.defaultnodesettings.SettingsModelPassword;
import org.knime.core.node.defaultnodesettings.SettingsModelString;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKTReader;
import org.opengis.feature.simple.SimpleFeature;
import org.opengis.feature.simple.SimpleFeatureType;
import org.opengis.referencing.crs.CoordinateReferenceSystem;

import ch.res_ear.samthiriot.knime.shapefilesaswkt.DataTableToGeotoolsMapper;
import ch.res_ear.samthiriot.knime.shapefilesaswkt.NodeWarningWriter;
import ch.res_ear.samthiriot.knime.shapefilesaswkt.SpatialUtils;


/**
 * This is the model implementation of WriteWKTAsShapefile.
 * Stores the WKT data as a shapefile.
 *
 * @author Samuel Thiriot
 */
public class WriteWKTIntoDBNodeModel extends NodeModel {
    
	final static String ENCRYPTION_KEY = "KnimeWKT";

	protected SettingsModelString m_dbtype = new SettingsModelString("dbtype", "postgis");
	protected SettingsModelString m_host = new SettingsModelString("host", "127.0.0.1");
	protected SettingsModelIntegerBounded m_port = new SettingsModelIntegerBounded("port", 5432, 1, 65535);
	protected SettingsModelString m_schema = new SettingsModelString("schema", "public");
	protected SettingsModelString m_database = new SettingsModelString("database", "database");
	protected SettingsModelString m_user = new SettingsModelString("user", "postgres");
	protected SettingsModelString m_password = new SettingsModelPassword("password", ENCRYPTION_KEY, "postgres");
	protected SettingsModelString m_layer = new SettingsModelString("layer", "my_geometries");
    protected SettingsModelBoolean m_checkWritten = new SettingsModelBoolean("check_written", true);

    /**
     * Count of entities to write at once
     */
    final static int BUFFER = 5000;
	
	

    /**
     * Constructor for the node model.
     */
    protected WriteWKTIntoDBNodeModel() {
    
        super(1, 0);
    }
    

	protected DataStore openDataStore(ExecutionContext exec) throws InvalidSettingsException {

		// @see http://docs.geotools.org/stable/userguide/library/jdbc/postgis.html
        Map<String, Object> params = new HashMap<>();
        params.put("dbtype", 	m_dbtype.getStringValue());
        params.put("host", 		m_host.getStringValue());
        params.put("port",  	m_port.getIntValue());
        params.put("schema", 	m_schema.getStringValue());
        params.put("database", 	m_database.getStringValue());
        params.put("user", 		m_user.getStringValue());
        params.put("passwd", 	m_password.getStringValue());

        //params.put(PostgisDataStoreFactory.LOOSEBBOX, true );
        //params.put(PostgisDataStoreFactory.PREPARED_STATEMENTS, true );
        DataStore dataStore;
		try {
			final String dbg = "opening database: "+params.get("user")+"@"+params.get("host")+":"+params.get("port");
			if (exec != null) exec.setMessage(dbg);
	        getLogger().info(dbg);
	        dataStore = DataStoreFinder.getDataStore(params);
		} catch (IOException e1) {
			e1.printStackTrace();
			throw new InvalidSettingsException("Unable to open the url as a shape file: "+e1.getMessage());
		}

		return dataStore;
	}
	

	protected String getSchemaName(DataStore datastore) throws InvalidSettingsException {

		final String layer = m_layer.getStringValue().trim();
		
		Set<String> typeNames = new HashSet<>();
		try {
			typeNames.addAll(Arrays.asList(datastore.getTypeNames()));
		} catch (IOException e) {
			e.printStackTrace();
			throw new RuntimeException("error when trying to read the layers: "+e.getMessage(), e);
		}
		
		//if (!typeNames.contains(layer))
		//	throw new InvalidSettingsException("There is no layer named \""+layer+"\" in this datastore");
		
		return layer;
	}


	@Override
	protected DataTableSpec[] configure(final DataTableSpec[] inSpecs) throws InvalidSettingsException {
		
		final String layer = m_layer.getStringValue();
	
		if (layer == null)
			throw new InvalidSettingsException("please select one layer to read");
	
		if (inSpecs.length < 1)
			throw new InvalidSettingsException("missing input table");
			
		if (!SpatialUtils.hasGeometry(inSpecs[0]))
			throw new InvalidSettingsException("the input table contains no WKT geometry");
		
		return new DataTableSpec[] {};
	}

    /**
     * {@inheritDoc}
     */
    @Override
    protected BufferedDataTable[] execute(
			    		final BufferedDataTable[] inData,
			            final ExecutionContext exec) throws Exception {

    	final BufferedDataTable inputPopulation = inData[0];
    	

    	if (!SpatialUtils.hasGeometry(inputPopulation.getDataTableSpec()))
    		throw new IllegalArgumentException("the input table contains no spatial data (no column named "+SpatialUtils.GEOMETRY_COLUMN_NAME+")");
    	
    	if (!SpatialUtils.hasCRS(inputPopulation.getDataTableSpec()))
    		throw new IllegalArgumentException("the input table contains spatial data but no Coordinate Reference System");
    	    	
    	final String layerName = m_layer.getStringValue().trim();
    	if (layerName.isEmpty())
    		throw new IllegalArgumentException("please provide a layer name");
    		
    	CoordinateReferenceSystem crsOrig = SpatialUtils.decodeCRS(inputPopulation.getSpec());
    	
        
    	NodeWarningWriter warnings = new NodeWarningWriter(getLogger());

    	// open the resulting datastore
    	DataStore datastore = openDataStore(exec);

    	// copy the input population into a datastore
    	exec.setMessage("storing entities");
        
		SimpleFeatureTypeBuilder builder = new SimpleFeatureTypeBuilder();
        builder.setName(layerName);
        builder.setCRS(crsOrig); 
        
        Class<?> geomClassToBeStored = SpatialUtils.detectGeometryClassFromData(	
        										inputPopulation, 
        										SpatialUtils.GEOMETRY_COLUMN_NAME);
        
        // add attributes in order
        builder.add(
        		"geom", //SpatialUtils.GEOMETRY_COLUMN_NAME,
        		geomClassToBeStored
        		);
        
        // create mappers
        List<DataTableToGeotoolsMapper> mappers = inputPopulation
        												.getDataTableSpec()
        												.stream()
        												.filter(colspec -> !SpatialUtils.GEOMETRY_COLUMN_NAME.equals((colspec.getName())))
        												.map(colspec -> new DataTableToGeotoolsMapper(
        														warnings, 
        														colspec))
        												.collect(Collectors.toList());
        // add those to the builder type
        mappers.forEach(mapper -> mapper.addAttributeForSpec(builder));
        
        
        // build the type
        final SimpleFeatureType type = builder.buildFeatureType();
        // get or create the type in the file store
        try {
			datastore.getSchema(type.getName());
		} catch (IOException e) {
			datastore.createSchema(type);	
		}
		// retrieve it 
		SimpleFeatureSource featureSource = datastore.getFeatureSource(layerName);
        if (!(featureSource instanceof SimpleFeatureStore)) {
            throw new IllegalStateException("Modification not supported");
        }
        SimpleFeatureStore featureStore = (SimpleFeatureStore) featureSource;

        // identify the id of the geom column, that we will not use as a standard one
        final int idxColGeom = inputPopulation.getDataTableSpec().findColumnIndex(SpatialUtils.GEOMETRY_COLUMN_NAME);
		
        // prepare classes to create Geometries from WKT
        
        GeometryFactory geomFactory = JTSFactoryFinder.getGeometryFactory( null );
        WKTReader reader = new WKTReader(geomFactory);
        
        SimpleFeatureBuilder featureBuilder = new SimpleFeatureBuilder(type);
        
        Transaction transaction = new DefaultTransaction();
        featureStore.setTransaction(transaction);
        
        // the buffer of spatial features to be added soon (it's quicker to add several lines than only one)
		List<SimpleFeature> toStore = new ArrayList<>(BUFFER);
		
        CloseableRowIterator itRow = inputPopulation.iterator();
        try {
	        int currentRow = 0;
	        while (itRow.hasNext()) {
	        	final DataRow row = itRow.next();
	        	
	        	// process the geom column
	        	final DataCell cellGeom = row.getCell(idxColGeom);
	        	if (cellGeom.isMissing()) {
	        		// no geometry
	        		continue; // skip lines without geom
	        	}
	        	try {
		
		        	Geometry geom = reader.read(cellGeom.toString());
		        	featureBuilder.add(geom);
				} catch (ParseException e) {
					e.printStackTrace();
					throw new RuntimeException(e);
				}
	        	
	        	int colId = 0;
	        	for (int i=0; i<row.getNumCells(); i++) {
	        		
	        		if (i == idxColGeom) {
	        			// skip the column with geom
	        		} else {
	        			// process as a standard column
	        			featureBuilder.add(mappers.get(colId++).getValue(row.getCell(i)));
	        		}
	        	}
	        	
	        	// build this feature
	            SimpleFeature feature = featureBuilder.buildFeature(Integer.toString(currentRow)); // row.getKey().getString()
	            // add this feature to the buffer
	            if (!toStore.add(feature))
	            	warnings.warn("unknown problem when adding feature, it will be missing : "+feature);
	            	
	            if (toStore.size() >= BUFFER) {
	        		exec.checkCanceled();
	        		exec.setMessage("writing "+toStore.size()+" entities");
	            	featureStore.addFeatures( new ListFeatureCollection( type, toStore));
	    	        //transaction.commit();
	    	        //transaction.close();
	            	toStore.clear();
	            	

	    	        transaction = new DefaultTransaction();
	            }
	            
	            if (currentRow % 10 == 0) {
	        		exec.setProgress((double)currentRow / inputPopulation.size(), "processing row "+currentRow);
	        		exec.checkCanceled();
	            }
	            currentRow++;
	            
	        }

	        // store last lines
	        if (!toStore.isEmpty()) {
        		exec.setMessage("writing "+toStore.size()+" entities (final)");
	        	featureStore.addFeatures( new ListFeatureCollection( type, toStore));
	        }
	        
	    	getLogger().info("commiting changes to database");
	        transaction.commit();

	        // clear mem
        	toStore.clear();

	    	getLogger().info("done");
	    	exec.setProgress(1.0);
	       
        } catch (RuntimeException e) {
        	if (transaction != null) {
                try {
                	getLogger().info("applying rollback to attempt remove our changes in the database");
                	transaction.rollback();
                } catch (IOException doubleEeek) {
                    // rollback failed
                	getLogger().warn("error during rollback; maybe some garbage will remain in the database "+e.getMessage());
                }
        	}
        } finally {

        	if (transaction != null)
        		transaction.close();
        	
        	if (itRow != null)
        		itRow.close();
        	
            // close datastore
            datastore.dispose();
        }
        
        setWarningMessage(warnings.buildWarnings());

        
        
        // check the features were created (based on our tests, we got cases with no error but also nothing written!)
        if (m_checkWritten.getBooleanValue()) {
        	DataStore datastoreRead = openDataStore(exec);
        	try {
	        	if (datastore == datastoreRead)
	        		getLogger().warn("got the same datastore, cannot test...");
	        	else {
		        	exec.setMessage("checking the count of entities in the database");
		    		SimpleFeatureSource featureSourceRead = datastoreRead.getFeatureSource(layerName);
		    		SimpleFeatureCollection collectionRead = featureSourceRead.getFeatures();
		    		if (collectionRead.size() < inputPopulation.size())
		    			throw new RuntimeException(
		    					"we did not wrote the expected count of entities: there were "+
		    							inputPopulation.size()+" lines, but only "+collectionRead.size()+
		    							" features were created");
	        	}
        	} finally {
        		if (datastoreRead != null)
        			datastoreRead.dispose();
        	}
        }
        
        
        return new BufferedDataTable[]{};
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void reset() {
       
    	// nothing to do
    }

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void saveSettingsTo(final NodeSettingsWO settings) {
	

		m_dbtype.saveSettingsTo(settings);
		m_host.saveSettingsTo(settings);
		m_port.saveSettingsTo(settings);
		m_schema.saveSettingsTo(settings);
		m_database.saveSettingsTo(settings);
		m_user.saveSettingsTo(settings);
		m_password.saveSettingsTo(settings);
		m_layer.saveSettingsTo(settings);
		m_checkWritten.saveSettingsTo(settings);

	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void loadValidatedSettingsFrom(final NodeSettingsRO settings) throws InvalidSettingsException {
		
		m_dbtype.loadSettingsFrom(settings);
		m_host.loadSettingsFrom(settings);
		m_port.loadSettingsFrom(settings);
		m_schema.loadSettingsFrom(settings);
		m_database.loadSettingsFrom(settings);
		m_user.loadSettingsFrom(settings);
		m_password.loadSettingsFrom(settings);
		m_layer.loadSettingsFrom(settings);
		m_checkWritten.loadSettingsFrom(settings);
		
	}

	/**
	 * {@inheritDoc}
	 */
	@Override
	protected void validateSettings(final NodeSettingsRO settings) throws InvalidSettingsException {
		
		m_dbtype.validateSettings(settings);
		m_host.validateSettings(settings);
		m_port.validateSettings(settings);
		m_schema.validateSettings(settings);
		m_database.validateSettings(settings);
		m_user.validateSettings(settings);
		m_password.validateSettings(settings);
		m_layer.validateSettings(settings);
		m_checkWritten.validateSettings(settings);
	}

    /**
     * {@inheritDoc}
     */
    @Override
    protected void loadInternals(final File internDir,
            final ExecutionMonitor exec) throws IOException,
            CanceledExecutionException {
        
    	// nothing to do

    }
    
    /**
     * {@inheritDoc}
     */
    @Override
    protected void saveInternals(final File internDir,
            final ExecutionMonitor exec) throws IOException,
            CanceledExecutionException {
       
    	// nothing to do
    }


}

