package org.janelia.saalfeldlab.n5.spark;

import java.io.IOException;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import org.apache.spark.SparkConf;
import org.apache.spark.api.java.JavaSparkContext;
import org.janelia.saalfeldlab.n5.DatasetAttributes;
import org.janelia.saalfeldlab.n5.N5FSWriter;
import org.janelia.saalfeldlab.n5.N5Writer;
import org.janelia.saalfeldlab.n5.imglib2.N5Utils;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;

import bdv.export.Downsample;
import net.imglib2.FinalInterval;
import net.imglib2.Interval;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.img.array.ArrayImgFactory;
import net.imglib2.img.cell.CellGrid;
import net.imglib2.type.NativeType;
import net.imglib2.type.numeric.RealType;
import net.imglib2.util.Intervals;
import net.imglib2.util.Util;
import net.imglib2.view.Views;

public class N5DownsamplerSpark
{
	private static final int MAX_PARTITIONS = 15000;

	/**
	 * Downsamples the given input dataset of an N5 container with respect to the given downsampling factors.
	 * The output dataset will be created within the same N5 container.
	 *
	 * @param sparkContext
	 * @param n5Supplier
	 * @param inputDatasetPath
	 * @param outputDatasetPath
	 * @param downsamplingFactors
	 * @throws IOException
	 */
	public static < T extends NativeType< T > & RealType< T > > void downsample(
			final JavaSparkContext sparkContext,
			final N5WriterSupplier n5Supplier,
			final String inputDatasetPath,
			final String outputDatasetPath,
			final int[] downsamplingFactors ) throws IOException
	{
		if ( Arrays.stream( downsamplingFactors ).max().getAsInt() <= 1 )
			throw new IllegalArgumentException( "Invalid downsampling factors: " + Arrays.toString( downsamplingFactors ) );

		final N5Writer n5 = n5Supplier.get();
		if ( !n5.datasetExists( inputDatasetPath ) )
			throw new IllegalArgumentException( "Input N5 dataset " + inputDatasetPath + " does not exist" );
		if ( n5.datasetExists( outputDatasetPath ) )
			throw new IllegalArgumentException( "Output N5 dataset " + outputDatasetPath + " already exists" );

		final DatasetAttributes inputAttributes = n5.getDatasetAttributes( inputDatasetPath );
		final int dim = inputAttributes.getNumDimensions();

		if ( dim != downsamplingFactors.length )
			throw new IllegalArgumentException( "Specified downsampling factors do not match data dimensionality." );

		final long[] outputDimensions = new long[ dim ];
		for ( int d = 0; d < dim; ++d )
			outputDimensions[ d ] = inputAttributes.getDimensions()[ d ] / downsamplingFactors[ d ];

		if ( Arrays.stream( outputDimensions ).min().getAsLong() < 1 )
			throw new IllegalArgumentException( "Degenerate output dimensions: " + Arrays.toString( outputDimensions ) );

		final int[] outputBlockSize = inputAttributes.getBlockSize().clone();
		n5.createDataset(
				outputDatasetPath,
				outputDimensions,
				outputBlockSize,
				inputAttributes.getDataType(),
				inputAttributes.getCompression()
			);

		final CellGrid outputCellGrid = new CellGrid( outputDimensions, outputBlockSize );
		final long numDownsampledBlocks = Intervals.numElements( outputCellGrid.getGridDimensions() );
		final List< Long > blockIndexes = LongStream.range( 0, numDownsampledBlocks ).boxed().collect( Collectors.toList() );

		sparkContext.parallelize( blockIndexes, Math.min( blockIndexes.size(), MAX_PARTITIONS ) ).foreach( blockIndex ->
		{
			final CellGrid cellGrid = new CellGrid( outputDimensions, outputBlockSize );
			final long[] blockGridPosition = new long[ cellGrid.numDimensions() ];
			cellGrid.getCellGridPositionFlat( blockIndex, blockGridPosition );

			final long[] sourceMin = new long[ dim ], sourceMax = new long[ dim ], targetMin = new long[ dim ], targetMax = new long[ dim ];
			final int[] cellDimensions = new int[ dim ];
			cellGrid.getCellDimensions( blockGridPosition, targetMin, cellDimensions );
			for ( int d = 0; d < dim; ++d )
			{
				targetMax[ d ] = targetMin[ d ] + cellDimensions[ d ] - 1;
				sourceMin[ d ] = targetMin[ d ] * downsamplingFactors[ d ];
				sourceMax[ d ] = targetMax[ d ] * downsamplingFactors[ d ] + ( downsamplingFactors[ d ] - 1 );
			}

			final Interval sourceInterval = new FinalInterval( sourceMin, sourceMax );
			final Interval targetInterval = new FinalInterval( targetMin, targetMax );

			final N5Writer n5Local = n5Supplier.get();
			final RandomAccessibleInterval< T > previousScaleLevelImg = N5Utils.open( n5Local, inputDatasetPath );
			final RandomAccessibleInterval< T > source = Views.offsetInterval( previousScaleLevelImg, sourceInterval );
			final RandomAccessibleInterval< T > target = new ArrayImgFactory< T >().create( targetInterval, Util.getTypeFromInterval( source ) );
			Downsample.downsample( source, target, downsamplingFactors );
			N5Utils.saveBlock( target, n5Local, outputDatasetPath, blockGridPosition );
		} );
	}


	public static void main( final String... args ) throws IOException, CmdLineException
	{
		final Arguments parsedArgs = new Arguments( args );

		try ( final JavaSparkContext sparkContext = new JavaSparkContext( new SparkConf()
				.setAppName( "N5DownsamplerSpark" )
				.set( "spark.serializer", "org.apache.spark.serializer.KryoSerializer" )
			) )
		{
			final N5WriterSupplier n5Supplier = () -> new N5FSWriter( parsedArgs.getN5Path() );
			downsample(
					sparkContext,
					n5Supplier,
					parsedArgs.getInputDatasetPath(),
					parsedArgs.getOutputDatasetPath(),
					parsedArgs.getDownsamplingFactors()
				);
		}
		System.out.println( "Done" );
	}

	private static class Arguments implements Serializable
	{
		private static final long serialVersionUID = -1467734459169624759L;

		@Option(name = "-n", aliases = { "--n5Path" }, required = true,
				usage = "Path to an N5 container.")
		private String n5Path;

		@Option(name = "-i", aliases = { "--inputDatasetPath" }, required = true,
				usage = "Path to the input dataset within the N5 container (e.g. data/group/s0).")
		private String inputDatasetPath;

		@Option(name = "-o", aliases = { "--outputDatasetPath" }, required = true,
				usage = "Path to the output dataset to be created (e.g. data/group/s1).")
		private String outputDatasetPath;

		@Option(name = "-f", aliases = { "--factor" }, required = false,
				usage = "Downsampling factors.")
		private String downsamplingFactors;

		public Arguments( final String... args ) throws CmdLineException
		{
			new CmdLineParser( this ).parseArgument( args );
		}

		public String getN5Path() { return n5Path; }
		public String getInputDatasetPath() { return inputDatasetPath; }
		public String getOutputDatasetPath() { return outputDatasetPath; }
		public int[] getDownsamplingFactors() { return parseIntArray( downsamplingFactors ); }

		private static int[] parseIntArray( final String str )
		{
			if ( str == null )
				return null;

			final String[] tokens = str.split( "," );
			final int[] values = new int[ tokens.length ];
			for ( int i = 0; i < values.length; i++ )
				values[ i ] = Integer.parseInt( tokens[ i ] );
			return values;
		}
	}
}
