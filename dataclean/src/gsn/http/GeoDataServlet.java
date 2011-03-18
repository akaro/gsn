package gsn.http;

import com.vividsolutions.jts.io.ParseException;
import gsn.Main;
import gsn.http.ac.DataSource;
import gsn.http.ac.User;
import org.apache.log4j.Logger;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Properties;

public class GeoDataServlet extends HttpServlet {

    private static transient Logger logger = Logger.getLogger(GeoDataServlet.class);
    private boolean usePostGIS = true; // by default use JTS
    private User user = null;

    public void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {

        CheckGISToolkitToUse();

        try {

            if (Main.getContainerConfig().isAcEnabled()) {
                HttpSession session = request.getSession();
                user = (User) session.getAttribute("user");
                response.setHeader("Cache-Control", "no-store");
                response.setDateHeader("Expires", 0);
                response.setHeader("Pragma", "no-cache");
            }

            String env = HttpRequestUtils.getStringParameter("env", null, request); // e.g. "POLYGON ((0 0, 0 100, 100 100, 100 0, 0 0))";
            String query = HttpRequestUtils.getStringParameter("query", null, request);

            if (usePostGIS)
                response.getWriter().write(runPostGIS(env, query));
            else
                response.getWriter().write(runJTS(env, query));

        } catch (ParseException e) {
            logger.warn(e.getMessage(), e);
        }
    }

    /*
   * Searches within the list of provided sensors
   * for the ones matching AC credentials for the current user
   * */
    public String getMatchingSensors(ArrayList<String> sensors) {
        StringBuilder matchingSensors = new StringBuilder();

        for (String vsName : sensors) {
            if (!Main.getContainerConfig().isAcEnabled() || (user != null && (user.hasReadAccessRight(vsName) || user.isAdmin()))) {
                matchingSensors.append(vsName);
                matchingSensors.append(GetSensorDataWithGeo.SEPARATOR);
            }
        }
        matchingSensors.setLength(matchingSensors.length() - 1); // remove the last SEPARATOR
        return matchingSensors.toString();
    }

    public String runJTS(String env, String query) throws ParseException {

        StringBuilder response = new StringBuilder();

        GetSensorDataWithGeo.buildGeoIndex();

        response.append("List of all sensors: \n" + GetSensorDataWithGeo.getListOfSensors() + "\n");
        response.append("Envelope: " + env + "\n");

        ArrayList<String> sensors = GetSensorDataWithGeo.getListOfSensors(env);
        String matchingSensors = getMatchingSensors(sensors);

        response.append("List of all sensors within envelope: \n" + matchingSensors + "\n");

        response.append("Query:" + query + "\n");
        response.append("Query result: \n");
        response.append(GetSensorDataWithGeo.executeQuery(env, matchingSensors.toString(), query));

        return response.toString();

    }

    public String runPostGIS(String env, String query) throws ParseException {

        StringBuilder response = new StringBuilder();

        GetSensorDataWithGeoPostGIS.buildGeoIndex();

        response.append("List of all sensors: \n" + GetSensorDataWithGeoPostGIS.getListOfSensors() + "\n");
        response.append("Envelope: " + env + "\n");

        ArrayList<String> sensors = GetSensorDataWithGeoPostGIS.getListOfSensors(env);
        String matchingSensors = getMatchingSensors(sensors);

        response.append("List of all sensors within envelope: \n" + matchingSensors + "\n");

        response.append("Query:" + query + "\n");
        response.append("Query result: \n");
        response.append(GetSensorDataWithGeoPostGIS.executeQuery(env, matchingSensors.toString(), query));

        return response.toString();

    }

    public void doPost(HttpServletRequest request, HttpServletResponse res) throws ServletException, IOException {
        doGet(request, res);
    }

    public void CheckGISToolkitToUse() {

        usePostGIS = false; // use JTS by default

        Properties p = new Properties();
        try {
            p.load(new FileInputStream(GetSensorDataWithGeoPostGIS.CONF_SPATIAL_PROPERTIES_FILE));
        }
        catch (IOException e) {
            p = null;
            logger.warn(e.getMessage(), e);
        }

        if (p != null) {
            String typeOFGIS = p.getProperty("type");
            if (typeOFGIS != null)
                if (typeOFGIS.trim().toLowerCase().equals("postgis")) {
                    usePostGIS = true;
                }
        }

        if (usePostGIS)
            logger.warn("Using PostGIS");
        else
            logger.warn("Using JTS");
    }


}