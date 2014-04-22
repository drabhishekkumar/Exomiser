package de.charite.compbio.exomiser.filter;


import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

import jannovar.exome.Variant;

import de.charite.compbio.exomiser.common.FilterType;
import de.charite.compbio.exomiser.exception.ExomizerInitializationException;
import de.charite.compbio.exomiser.exome.VariantEvaluation;

/**
 * Filter variants according to their frequency. We use the thousand genomes and the 
 * ESP (exome server project) data. The filter is implemented with an SQL query.
 * @author Peter N Robinson
 * @version 0.11 (December 29, 2013)
 */
public class QualityFilter implements IFilter {
    /** Threshold for filtering. Retain only those variants whose PHRED
	variant call quality is at least as good. The default is 1. */
    private float quality_threshold = 1.0f;
    /**
     * Minimum number of reads supporting the ALT call. There must be at least this number of
     * reads in each direction. */
    private int minAltReadThresold = 0;
    /** A list of messages that can be used to create a display in a HTML page or elsewhere. */
    private List<String> messages = null;
    /** Number of variants before filtering was applied. */
    private int n_before;
    /** Number of variants after filtering was applied. */
    private int n_after;

    public QualityFilter() throws ExomizerInitializationException  {
	this.messages = new ArrayList<String>();
     }

    /**
     * @param par A String containing the PHRED quality threshold (e.g., 30).
     */
     public void set_parameters(String par) throws ExomizerInitializationException
     {
	 try {
	     this.quality_threshold  = Float.parseFloat(par);
	     QualityTriage.set_frequency_threshold(quality_threshold);
	     this.messages.add(String.format("PHRED quality &ge;%.1f", quality_threshold));
	 } catch (NumberFormatException e) {
	     String  msg = "Could not parse frequency parameter for quality filter: \"" + par + "\"";
	     throw new ExomizerInitializationException(msg);
	 }
     }

    @Override public String getFilterName() { return "Quality filter"; }

    /**  Flag for output field representing the QUAL column of the VCF file. */
    @Override public FilterType getFilterTypeConstant() { return FilterType.QUALITY_FILTER; } 

    /**
     * @return list of messages representing process, result, and if any, errors of frequency filtering. 
     */
    @Override public List<String> getMessages() {
	return this.messages;
    }

    /** Get number of variants before filter was applied */
    public int getBefore() { return this.n_before; }
    /** Get number of variants after filter was applied */
    public int getAfter() { return this.n_after; }

    
    @Override public void filter_list_of_variants(List<VariantEvaluation> variant_list)
    {
	Iterator<VariantEvaluation> it = variant_list.iterator();
	
	this.n_before = variant_list.size();
	while (it.hasNext()) {
	    VariantEvaluation ve = it.next();
	    Variant v = ve.getVariant();
	    QualityTriage qt = new QualityTriage(v.getVariantPhredScore());
	    if (! qt.passesFilter() ) {
		// Variant is not of good quality, discard it.
		it.remove();
	    } else {
		// We passed the filter (Variant has good enough quality).
		ve.addFilterTriage(qt, FilterType.QUALITY_FILTER);
	    }
	}
	this.n_after =  variant_list.size();
    }

    public boolean display_in_HTML() { return true; }

    /**
     * Not needed in this class.
     * @param connection An SQL (postgres) connection that was initialized elsewhere.
     */
    @Override public void setDatabaseConnection(java.sql.Connection connection) { /* no-op. */ }

}