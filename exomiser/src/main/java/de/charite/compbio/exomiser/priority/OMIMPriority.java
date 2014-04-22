package de.charite.compbio.exomiser.priority;




import java.util.List;
import java.util.ArrayList;
import java.util.Iterator;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import de.charite.compbio.exomiser.common.FilterType;
import de.charite.compbio.exomiser.exome.Gene;
import de.charite.compbio.exomiser.exception.ExomizerInitializationException;
import de.charite.compbio.exomiser.exception.ExomizerSQLException;



/**
 * This class is designed to do two things. First, it will add annotations to
 * genes based on their annotations to OMIM or Orphanet disease entries in the
 * exomiser database (Note that the app PopulateExomiserDatabase.jar, from this
 * software package is used to put the data into the database; see there for
 * more information). The tables <b>omim</b> and <b>orphanet</b> are used to 
 * store/retrieve this information. The second purpose of this classis to check
 * whether the variants found in the VCF file match with the mode of inheritance
 * listed for the disease (column "inheritance" of the omim table; TODO-add similar
 * functionality for Orphanet). Thus, if we find a heterozygous mutation but the
 * disease is autosomal recessive, then it the corresponding disease/gene is not
 * a good candidate, and its OMIM relevance score is reduced by a factor of 50%.
 * See the function {@link #getInheritanceFactor} for details on this weighting scheme.
 * @author Peter N Robinson
 * @version 0.16 (28 January,2014)
 */
public class OMIMPriority implements IPriority {

     /** Database handle to the postgreSQL database used by this application. */
    private Connection connection=null;
    /** A prepared SQL statement for OMIM entries. */
    private PreparedStatement omimQuery = null;
    /** A prepared SQL statement for Orphanet entries. */
    private PreparedStatement orphanetQuery = null;

    /** A list of messages that can be used to create a display in a HTML page or elsewhere. */
    private List<String> messages = null;



    public OMIMPriority() throws ExomizerInitializationException  {
	this.messages = new ArrayList<String>();
    }

    
    
    @Override public String getPriorityName() { return "OMIM"; }
    /**  Flag for output field representing OMIM. */
    @Override public FilterType getPriorityTypeConstant() { return FilterType.OMIM_FILTER; } 

     /**
     * @return list of messages representing process, result, and if any, errors of frequency filtering. 
     */
    public List<String> getMessages() {
	return this.messages;
    }

   

    /**
     * For now, this method just annotates each gene with OMIM data, if
     * available, and shows a link in the HTML output. However, we can 
     * use this method to implement a Phenomizer-type prioritization at
     * a later time point.
     * @param gene_list A list of the {@link exomizer.exome.Gene Gene} objects
     * that have suvived the filtering (i.e., have rare, potentially pathogenic variants).
     */
    @Override 
    public void prioritize_list_of_genes(List<Gene> gene_list)
    {
	Iterator<Gene> it = gene_list.iterator();
	while (it.hasNext()) {
	    Gene g = it.next();
	    try {
		OMIMRelevanceScore mimrel = retrieve_omim_data(g);
		g.addRelevanceScore(mimrel,FilterType.OMIM_FILTER);
	    } catch (ExomizerSQLException e) {
		this.messages.add(e.toString());
	    }
	}
    }


    /**
     * Note that if there is no EntrezGene IDfor this gene, its field entrezGeneID
     * will be set to -10. If this is the case, we return an empty but initialized
     * RelevanceScore object. Otherwise, we 
     * retrieve a list of all OMIM and Orphanet diseases associated with the
     * entrez Gene.
     * @param g The gene which is being evaluated.
     */
    private OMIMRelevanceScore retrieve_omim_data(Gene g) throws ExomizerSQLException  {
	OMIMRelevanceScore rel = new OMIMRelevanceScore();
	int entrez = g.getEntrezGeneID();
	if (entrez<0) return rel; /* Return an empty relevance score object. */
	try {
	    ResultSet rs = null;
	    this.omimQuery.setInt(1,entrez);
	    rs = omimQuery.executeQuery();
	   
	    while ( rs.next() ) { /* The way the db was constructed, there is just one line for each such query. */
		//  phenmim,genemim,diseasename,type"+
		int phenmim = rs.getInt(1);
		int genemim = rs.getInt(2);
		String disease = rs.getString(3);
		char typ = rs.getString(4).charAt(0);
		char inheritance = rs.getString(5).charAt(0);
		float factor=getInheritanceFactor(g,inheritance);
		// System.out.println(preparedQuery);
		rel.addRow(phenmim,genemim,disease,typ,inheritance, factor);
	    }
	    rs.close();
	    rs = null; 
	} catch(SQLException e) {
	    throw new ExomizerSQLException("Error executing OMIM query: " + e);
	}
	// Now try to get the Orphanet data 
	try {
	    ResultSet rs2 = null;
	    this.orphanetQuery.setInt(1,entrez);
	    rs2 = orphanetQuery.executeQuery();
	    while ( rs2.next() ) { 
		 int orphanum = rs2.getInt(1);
		 String disease = rs2.getString(2);
		 rel.addOrphanetRow(orphanum,disease);
		 }

	  } catch(SQLException e) {
	    System.out.println("Exception caused by Orphanet query!" + e);
	    throw new ExomizerSQLException("Error executing OMIM query: " + e);
	    }
   
	return rel;
    }


    /**
     * This function checks whether the mode of inheritance of the
     * disease matches the observed pattern of variants. That is,
     * if the disease is autosomal recessive and we have just one
     * heterozygous mutation, then the disease is probably not the correct
     * diagnosis, and we assign it a factor of 0.5. 
     * Note that hemizygous X chromosomal variants are usually called as
     * homozygous ALT in VCF files, and thus it is not reliable to 
     * distinguish between X-linked recessive and dominant inheritance. Therefore,
     * we return 1 for any gene with X-linked inheritance if the disease in question
     * is listed as X chromosomal.
     */
    private float getInheritanceFactor(Gene g,char inheritance) {
	if (inheritance=='U') {
	    /* inheritance unknown (not mentioned in OMIM or not annotated correctly in HPO */
	    return 1f;
	}  else if (g.is_consistent_with_dominant() && (inheritance=='D' || inheritance=='B') ) {
	    /* inheritance of disease is dominant or both (dominant/recessive) */
	    return 1f;
	}
	else if (g.is_consistent_with_recessive() && (inheritance=='R' || inheritance=='B') ) {
	    /* inheritance of disease is recessive or both (dominant/recessive) */
	    return 1f;
	} else if (g.is_X_chromosomal() && inheritance=='X') {
	    return 1f;
	} else if (inheritance=='Y') {
	    return 1f; /* Y chromosomal, rare. */
	} else if (inheritance=='M') {
	    return 1f; /* mitochondrial. */
	} else if (inheritance=='S') {
	    return 0.5f; /* gene only associated with somatic mutations */
	} else if (inheritance=='P') {
	    return 0.5f; /* gene only associated with polygenic */
	}
	else
	    return 0.5f;
    }



     /**
     *  Prepare the SQL query statements required for this filter.
     * <p>
     * SELECT phenmim,genemim,diseasename,type</br>
     * FROM omim</br>
     * WHERE gene_id  = ? </br>
     */
    private void setUpSQLPreparedStatement() throws ExomizerInitializationException
    {	
	String query = String.format("SELECT phenmim,genemim,diseasename,type,inheritance "+
				     "FROM omim " +
				     "WHERE gene_id = ?");
        try {
	    this.omimQuery  = connection.prepareStatement(query);
        } catch (SQLException e) {
	    String error = "Problem setting up OMIM SQL query:" + query;
	    throw new ExomizerInitializationException(error);
        }
	/* Now the same for Orphanet. */
	query = String.format("SELECT orphanumber,diseasename "+
			      "FROM orphanet " +
			      "WHERE entrezGeneID = ?");
	try {
	    this.orphanetQuery  = connection.prepareStatement(query);
        } catch (SQLException e) {
	    String error = "Problem setting up Orphanet SQL query:" + query;
	    throw new ExomizerInitializationException(error);
        }
    }

    
    
    /**
     * Initialize the database connection and call {@link #setUpSQLPreparedStatement}
     * @param connection A connection to a postgreSQL database from the exomizer or tomcat.
     */
     @Override public void setDatabaseConnection(java.sql.Connection connection) 
	throws ExomizerInitializationException
    {
	this.connection = connection;
	setUpSQLPreparedStatement();
    }

    
    /**
     * Since no filtering of prioritizing is done with the OMIM data
     * for now, it does not make sense to display this in the HTML 
     * table. */
    public boolean display_in_HTML() { return false; }

  
    public String getHTMLCode() { return "To Do"; }

     /** Get number of variants before filter was applied TODO */
    public int getBefore() {return 0; }
    /** Get number of variants after filter was applied TODO */
    public int getAfter() {return 0; }

 /**
     * Set parameters of prioritizer if needed.
     * @param par A String with the parameters (usually extracted from the cmd line) for this prioiritizer)
     */
    @Override public void setParameters(String par) {
	/* -- Nothing needed now */
    }
}