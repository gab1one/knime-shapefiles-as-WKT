## knime-shapefiles-as-WKT

### what is it?

This collection of nodes for the [KNIME scientific workflow engine](https://en.wikipedia.org/wiki/KNIME)
offer to manipulate [shapefiles](https://en.wikipedia.org/wiki/Shapefile) in KNIME. 
Geometries are decoded and manipulated as their [Well-Known Text representation](https://en.wikipedia.org/wiki/Well-known_text_representation_of_geometry) (WKT), which are stored as native KNIME Strings. 

The collection offers nodes to read shapefiles as KNIME data tables and store KNIME data tables as shapefiles.

### how does it work?

All the smart work is done by the wonderful [geotools](https://en.wikipedia.org/wiki/GeoTools) [JTS library](https://en.wikipedia.org/wiki/JTS_Topology_Suite).
We only do provide the integration of these features inside KNIME. 
We currently integrated the library in its stable version 20.1 .

## License

These nodes where developed for the [European Institute for Energy Research (EIFER)](https://www.eifer.kit.edu/).
They are notably used for Generation of Synthetic Populations (GoSP), in order to read spatial populations. 
These nodes are released as GPL v3; see the [Free Software Foundation presentation](https://www.gnu.org/licenses/quick-guide-gplv3.en.html) if you're not familiar with open-source licenses.

## Development environment

In order to create a development environment, follow the [instructions to create a KNIME development environment](https://github.com/knime/knime-sdk-setup).

