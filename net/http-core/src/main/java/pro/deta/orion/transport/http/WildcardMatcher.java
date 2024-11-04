package pro.deta.orion.transport.http;

/**
 * Utility class for matching strings with wildcard patterns.
 * The wildcard character '*' can match zero or more characters.
 */
public class WildcardMatcher {
    
    /**
     * Checks if the given text matches the pattern containing wildcards.
     * 
     * @param pattern The pattern that may contain wildcards (*)
     * @param text The text to match against the pattern
     * @return true if the text matches the pattern, false otherwise
     */
    public static boolean matches(String pattern, String text) {
        if (pattern == null || text == null) {
            return false;
        }
        
        // Create arrays for dynamic programming
        boolean[][] dp = new boolean[text.length() + 1][pattern.length() + 1];
        
        // Empty pattern matches empty string
        dp[0][0] = true;
        
        // Handle patterns starting with *
        for (int j = 1; j <= pattern.length(); j++) {
            if (pattern.charAt(j - 1) == '*') {
                dp[0][j] = dp[0][j - 1];
            }
        }
        
        // Fill the dp table
        for (int i = 1; i <= text.length(); i++) {
            for (int j = 1; j <= pattern.length(); j++) {
                if (pattern.charAt(j - 1) == '*') {
                    dp[i][j] = dp[i - 1][j] || dp[i][j - 1];
                } else if (pattern.charAt(j - 1) == text.charAt(i - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                }
            }
        }
        
        return dp[text.length()][pattern.length()];
    }
}